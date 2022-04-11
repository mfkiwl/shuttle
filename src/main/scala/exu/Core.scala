package saturn.exu

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.tile._
import freechips.rocketchip.util._
import freechips.rocketchip.rocket._
import freechips.rocketchip.rocket.Instructions._

import saturn.common._
import saturn.ifu._
import saturn.util._

class SaturnCore(tile: SaturnTile)(implicit p: Parameters) extends CoreModule()(p)
  with HasFPUParameters
{
  val io = IO(new Bundle {
    val hartid = Input(UInt(hartIdLen.W))
    val interrupts = Input(new CoreInterrupts())
    val imem  = new SaturnFrontendIO
    val dmem = new HellaCacheIO
    val ptw = Flipped(new DatapathPTWIO())
    val rocc = Flipped(new RoCCCoreIO())
    val trace = Vec(coreParams.retireWidth, Output(new TracedInstruction))
    val fcsr_rm = Output(UInt(FPConstants.RM_SZ.W))
  })

  val debug_tsc_reg = RegInit(0.U(64.W))
  debug_tsc_reg := debug_tsc_reg + 1.U
  dontTouch(debug_tsc_reg)
  val debug_irt_reg = RegInit(0.U(64.W))
  dontTouch(debug_irt_reg)

  // EventSet can't handle empty
  val events = new EventSets(Seq(new EventSet((mask, hit) => false.B, Seq(("placeholder", () => false.B)))))
  val csr = Module(new CSRFile(perfEventSets=events))
  csr.io := DontCare
  csr.io.ungated_clock := clock

  val fpParams = tileParams.core.fpu.get

  def checkExceptions(x: Seq[(Bool, UInt)]) =
    (x.map(_._1).reduce(_||_), PriorityMux(x))

  def encodeVirtualAddress(a0: UInt, ea: UInt) = if (vaddrBitsExtended == vaddrBits) ea else {
    // efficient means to compress 64-bit VA into vaddrBits+1 bits
    // (VA is bad if VA(vaddrBits) != VA(vaddrBits-1))
    val a = a0.asSInt >> vaddrBits
    val msb = Mux(a === 0.S || a === -1.S, ea(vaddrBits), !ea(vaddrBits-1))
    Cat(msb, ea(vaddrBits-1,0))
  }

  class Bypass extends Bundle {
    val valid = Bool()
    val dst = UInt(5.W)
    val data = UInt(64.W)
    val can_bypass = Bool()
  }

  val ex_bypasses: Seq[Bypass] = Seq.fill(retireWidth) { Wire(new Bypass) }
  val mem_bypasses: Seq[Bypass] = Seq.fill(retireWidth) { Wire(new Bypass) }
  val wb_bypasses: Seq[Bypass] = Seq.fill(retireWidth) { Wire(new Bypass) }
  val ll_bypass: Seq[Bypass] = Seq(Wire(new Bypass))
  val int_bypasses: Seq[Bypass] = ll_bypass ++ wb_bypasses ++ mem_bypasses ++ ex_bypasses

  val ex_uops_reg = Reg(Vec(retireWidth, Valid(new SaturnUOP)))
  val mem_uops_reg = Reg(Vec(retireWidth, Valid(new SaturnUOP)))
  val wb_uops_reg = Reg(Vec(retireWidth, Valid(new SaturnUOP)))

  val rrd_uops = Wire(Vec(retireWidth, Valid(new SaturnUOP)))
  val ex_uops = WireInit(ex_uops_reg)
  val mem_uops = WireInit(mem_uops_reg)
  val wb_uops = WireInit(wb_uops_reg)

  val rrd_stall = Wire(Vec(retireWidth, Bool()))
  val ex_stall = Wire(Bool())
  rrd_stall.foreach(_ := false.B)

  val flush_rrd = WireInit(VecInit(Seq.fill(retireWidth) { false.B }))
  assert(PopCount(flush_rrd).isOneOf(0.U, retireWidth.U))
  val flush_ex = WireInit(VecInit(Seq.fill(retireWidth) { false.B }))
  assert(PopCount(flush_ex).isOneOf(0.U, retireWidth.U))
  val kill_mem = WireInit(false.B)
  val kill_wb = WireInit(VecInit(Seq.fill(retireWidth) { false.B }))
  assert(!kill_wb(0))

  val rrd_fire = (rrd_uops zip rrd_stall) map { case (u, s) => u.valid && !s }

  val ex_bsy = ex_uops_reg.map(_.valid).reduce(_||_)
  val mem_bsy = mem_uops_reg.map(_.valid).reduce(_||_)
  val wb_bsy = wb_uops_reg.map(_.valid).reduce(_||_)

  io.imem.redirect_val := false.B
  io.imem.redirect_flush := false.B
  io.imem.flush_icache := false.B
  // rrd
  rrd_uops := io.imem.resp
  (rrd_uops zip io.imem.resp).foreach { case (l,r) =>
    val bitmanipMasks = Seq(
      ANDN, ORN, XNOR, CLZ, CLZW, CTZ, CTZW,
      PCNT, PCNTW, MAX, MAXU, MIN, MINU,
      SEXT_B, SEXT_H, ZEXT_H,
      ROL, ROLW, ROR, RORI, RORIW, RORW,
      ORC_B, REV8, PACK)
    val is_bitmanip = bitmanipMasks.map(m => r.bits.inst === m).reduce(_||_) && usingBitManip.B
    val pipelinedMul = true //pipeline VS iterative
    val decode_table = {
      (if (usingMulDiv) new MDecode(pipelinedMul) +: (xLen > 32).option(new M64Decode(pipelinedMul)).toSeq else Nil) ++:
      (if (usingAtomics) new ADecode +: (xLen > 32).option(new A64Decode).toSeq else Nil) ++:
      (if (fLen >= 32)    new FDecode +: (xLen > 32).option(new F64Decode).toSeq else Nil) ++:
      (if (fLen >= 64)    new DDecode +: (xLen > 32).option(new D64Decode).toSeq else Nil) ++:
      (if (minFLen == 16) new HDecode +: (xLen > 32).option(new H64Decode).toSeq ++: (fLen >= 64).option(new HDDecode).toSeq else Nil) ++:
      (usingRoCC.option(new RoCCDecode)) ++:
      (if (xLen == 32) new I32Decode else new I64Decode) +:
      (usingBitManip.option(new BitmanipDecode)) ++:
      (usingVM.option(new SVMDecode)) ++:
      (usingSupervisor.option(new SDecode)) ++:
      (usingDebug.option(new DebugDecode)) ++:
      (usingNMI.option(new NMIDecode)) ++:
        Seq(new FenceIDecode(false)) ++:
      Seq(new IDecode)
    } flatMap(_.table)

    def fp_decode(inst: UInt) = {
      val fp_decoder = Module(new FPUDecoder)
      fp_decoder.io.inst := inst
      fp_decoder.io.sigs
    }

    l.bits.ctrl.decode(r.bits.inst, decode_table)
    l.bits.fp_ctrl := fp_decode(r.bits.inst)
    l.bits.is_bitmanip := is_bitmanip
  }

  for (i <- 0 until retireWidth) {
    csr.io.decode(i).csr := rrd_uops(i).bits.inst(31,20)
  }

  val rrd_illegal_insn = Wire(Vec(retireWidth, Bool()))
  for (i <- 0 until retireWidth) {
    val uop = rrd_uops(i).bits
    val ctrl = uop.ctrl
    val inst = uop.inst
    val illegal_rm = inst(14,12).isOneOf(5.U, 6.U) || inst(14,12) === 7.U && io.fcsr_rm >= 5.U
    val fp_illegal = csr.io.decode(i).fp_illegal || illegal_rm
    val csr_en = uop.csr_en
    val csr_ren = uop.csr_ren
    val csr_wen = uop.csr_wen
    val csr_ren_illegal = csr_ren && csr.io.decode(i).read_illegal
    val csr_wen_illegal = csr_wen && csr.io.decode(i).write_illegal
    val sfence = ctrl.mem && ctrl.mem_cmd === M_SFENCE
    val system_insn = uop.system_insn

    rrd_uops(i).bits.flush_pipe := sfence || system_insn || (csr_wen && csr.io.decode(i).write_flush)

    rrd_illegal_insn(i) := (!ctrl.legal ||
      (ctrl.fp && fp_illegal) ||
      (ctrl.rocc && csr.io.decode(i).rocc_illegal) ||
      (csr_en && (csr_ren_illegal || csr_wen_illegal)) ||
      (!uop.rvc && ((sfence || system_insn) && csr.io.decode(i).system_illegal))
    )
  }
  for (i <- 0 until retireWidth) {
    val (xcpt, cause) = checkExceptions(List(
      (csr.io.interrupt         , csr.io.interrupt_cause),
      (io.imem.resp(i).bits.xcpt, io.imem.resp(i).bits.xcpt_cause),
      (rrd_illegal_insn(i)      , Causes.illegal_instruction.U)
    ))
    rrd_uops(i).bits.xcpt := xcpt
    rrd_uops(i).bits.xcpt_cause := cause

    when (xcpt) {
      rrd_uops(i).bits.ctrl.alu_fn := ALU.FN_ADD
      rrd_uops(i).bits.ctrl.alu_dw := DW_XPR
      rrd_uops(i).bits.ctrl.sel_alu1 := A1_RS1
      rrd_uops(i).bits.ctrl.sel_alu2 := A2_ZERO
      when (io.imem.resp(i).bits.xcpt) {
        rrd_uops(i).bits.ctrl.sel_alu1 := A1_PC
        rrd_uops(i).bits.ctrl.sel_alu2 := Mux(io.imem.resp(i).bits.edge_inst, A2_SIZE, A2_ZERO)
      }
    }
  }

  for (i <- 0 until retireWidth) {
    io.imem.resp(i).ready := !rrd_stall(i)
  }
  val iregfile = Reg(Vec(32, UInt(64.W)))
  val isboard = Reg(Vec(32, Bool()))
  val isboard_rrd_clear = WireInit(VecInit(0.U(32.W).asBools))
  val isboard_wb_set = WireInit(VecInit(0.U(32.W).asBools))
  val isboard_mem_set = WireInit(VecInit(0.U(32.W).asBools))
  val isboard_ex_set = WireInit(VecInit(0.U(32.W).asBools))
  for (i <- 0 until 32) {
    isboard(i) := (isboard(i) || isboard_wb_set(i) || isboard_mem_set(i) || isboard_ex_set(i)) && !isboard_rrd_clear(i)
  }
  isboard(0) := true.B
  val isboard_bsy = !isboard.reduce(_&&_)

  when (reset.asBool) {
    isboard.foreach(_ := true.B)
  }

  def bypass(bypasses: Seq[Bypass], rs: UInt): (Bool, UInt) = {
    val bypass_hit = WireInit(false.B)
    val bypass_data = WireInit(0.U(64.W))
    for (b <- bypasses) {
      when (b.valid && b.dst === rs) {
        bypass_hit := b.can_bypass
        bypass_data := b.data
      }
    }
    (bypass_hit, bypass_data)
  }

  val rrd_stall_data = Wire(Vec(retireWidth, Bool()))
  val rrd_irf_writes = Wire(Vec(retireWidth, Valid(UInt(5.W))))
  val enableMemALU = coreParams.asInstanceOf[SaturnCoreParams].enableMemALU && retireWidth > 1
  val rrd_mem_p0_can_forward = rrd_uops(0).bits.ctrl.wxd && rrd_uops(0).bits.uses_alu && enableMemALU.B
  for (i <- 0 until retireWidth) {
    val fp_ctrl = rrd_uops(i).bits.fp_ctrl
    val ctrl = rrd_uops(i).bits.ctrl
    val rs1 = rrd_uops(i).bits.rs1
    val rs2 = rrd_uops(i).bits.rs2
    val rs3 = rrd_uops(i).bits.rs3
    val rd = rrd_uops(i).bits.rd
    val (rs1_hit, rs1_bypass) = bypass(int_bypasses, rrd_uops(i).bits.rs1)
    val (rs2_hit, rs2_bypass) = bypass(int_bypasses, rrd_uops(i).bits.rs2)
    rrd_uops(i).bits.rs1_data := Mux(rs1 === 0.U, 0.U,
      Mux(rs1_hit, rs1_bypass, iregfile(rrd_uops(i).bits.rs1)))
    when (rrd_uops(i).bits.xcpt && rrd_uops(i).bits.xcpt_cause === Causes.illegal_instruction.U) {
      rrd_uops(i).bits.rs1_data := io.imem.resp(i).bits.raw_inst
    }
    rrd_uops(i).bits.rs2_data := Mux(rs2 === 0.U, 0.U,
      Mux(rs2_hit, rs2_bypass, iregfile(rrd_uops(i).bits.rs2)))

    val rs1_older_hazard = !isboard(rs1) && !rs1_hit
    val rs2_older_hazard = !isboard(rs2) && !rs2_hit
    val rd_older_hazard  = !isboard(rd)

    val rs1_w0_hit = rrd_irf_writes(0).valid && rrd_irf_writes(0).bits === rs1
    val rs2_w0_hit = rrd_irf_writes(0).valid && rrd_irf_writes(0).bits === rs2
    val rs1_can_forward_from_mem_p0 = (i>0).B && rrd_mem_p0_can_forward && rs1_w0_hit && rrd_uops(i).bits.uses_alu && !rrd_uops(i).bits.cfi && rs1 =/= 0.U
    val rs2_can_forward_from_mem_p0 = (i>0).B && rrd_mem_p0_can_forward && rs2_w0_hit && rrd_uops(i).bits.uses_alu && !rrd_uops(i).bits.cfi && rs2 =/= 0.U

    val rs1_same_hazard = rrd_irf_writes.take(i).zipWithIndex.map ({case (w,x) => {
      if (x == 0) {
        rs1_w0_hit && !rs1_can_forward_from_mem_p0
      } else {
        w.valid && w.bits === rs1
      }
    }}).orR
    val rs2_same_hazard = rrd_irf_writes.take(i).zipWithIndex.map ({case (w,x) => {
      if (x == 0) {
        rs2_w0_hit && !rs2_can_forward_from_mem_p0
      } else {
        w.valid && w.bits === rs2
      }
    }}).orR
    val rd_same_hazard  = rrd_irf_writes.take(i).map(w => w.valid && w.bits === rd).orR

    val rs1_memalu_hazard = ex_uops_reg.drop(1).map(u => u.valid && u.bits.rd === rs1 && u.bits.uses_memalu).orR
    val rs2_memalu_hazard = ex_uops_reg.drop(1).map(u => u.valid && u.bits.rd === rs2 && u.bits.uses_memalu).orR

    val rs1_data_hazard = (rs1_older_hazard || rs1_same_hazard || rs1_memalu_hazard) && ctrl.rxs1 && rs1 =/= 0.U
    val rs2_data_hazard = (rs2_older_hazard || rs2_same_hazard || rs2_memalu_hazard) && ctrl.rxs2 && rs2 =/= 0.U
    val rd_data_hazard  = (rd_older_hazard || rd_same_hazard) && ctrl.wxd && rd =/= 0.U

    rrd_stall_data(i) := (rs1_data_hazard || rs2_data_hazard || rd_data_hazard)
    rrd_uops(i).bits.uses_memalu := rrd_uops(i).bits.uses_alu && ((rs1_w0_hit && rs1_can_forward_from_mem_p0) || (rs2_w0_hit && rs2_can_forward_from_mem_p0)) && enableMemALU.B

    rrd_irf_writes(i).valid := rrd_uops(i).valid && rrd_uops(i).bits.ctrl.wxd
    rrd_irf_writes(i).bits := rrd_uops(i).bits.rd

    when (rrd_uops(i).valid && ctrl.fp) {
      when (fp_ctrl.ren1) {
        when (!fp_ctrl.swap12) { rrd_uops(i).bits.fra1 := rrd_uops(i).bits.rs1 }
        when ( fp_ctrl.swap12) { rrd_uops(i).bits.fra2 := rrd_uops(i).bits.rs1 }
      }
      when (fp_ctrl.ren2) {
        when ( fp_ctrl.swap12) { rrd_uops(i).bits.fra1 := rrd_uops(i).bits.rs2 }
        when ( fp_ctrl.swap23) { rrd_uops(i).bits.fra3 := rrd_uops(i).bits.rs2 }
        when (!fp_ctrl.swap12 && !fp_ctrl.swap23) { rrd_uops(i).bits.fra2 := rrd_uops(i).bits.rs2 }
      }
      when (fp_ctrl.ren3) {
        rrd_uops(i).bits.fra3 := rrd_uops(i).bits.rs3
      }
    }

    when (ctrl.mem_cmd.isOneOf(M_SFENCE, M_FLUSH_ALL)) {
      rrd_uops(i).bits.mem_size := Cat(rs2 =/= 0.U, rs1 =/= 0.U)
    }
  }

  val fsboard_bsy = Wire(Bool())
  var stall_due_to_older = false.B
  var rrd_found_mem = false.B
  for (i <- 0 until retireWidth) {
    val uop = rrd_uops(i).bits
    val ctrl = uop.ctrl

    val rrd_fence_stall = (i == 0).B && ((uop.system_insn || ctrl.fence || ctrl.amo || ctrl.fence_i) &&
      (ex_bsy || mem_bsy || wb_bsy || isboard_bsy || fsboard_bsy || !io.dmem.ordered || io.rocc.busy))
    val rrd_rocc_stall = (i == 0).B && ctrl.rocc && !io.rocc.cmd.ready
    val is_pipe0 = uop.system_insn || ctrl.fence || ctrl.amo || ctrl.fence_i || uop.csr_en || uop.sfence || ctrl.csr =/= CSR.N || uop.xcpt || uop.uses_fp || ctrl.mul || ctrl.div || uop.is_bitmanip || ctrl.rocc
    val is_youngest = uop.uses_brjmp || uop.next_pc.valid || uop.xcpt || uop.csr_en


    rrd_stall(i) := (
      (rrd_uops(i).valid && rrd_stall_data(i)) ||
      (rrd_fence_stall)                        ||
      (rrd_rocc_stall)                         ||
      (is_pipe0 && (i != 0).B)                 ||
      (rrd_found_mem && ctrl.mem)              ||
      (stall_due_to_older)                     ||
      (csr.io.csr_stall)                       ||
      (ex_stall)
    )

    stall_due_to_older = rrd_stall(i) || is_youngest
    rrd_found_mem = rrd_found_mem || ctrl.mem
  }

  for (i <- 0 until retireWidth) {
    when (rrd_fire(i)) {
      ex_uops_reg(i) := rrd_uops(i)
      ex_uops_reg(i).valid := rrd_uops(i).valid && !flush_rrd(i)
      when (rrd_uops(i).bits.ctrl.wxd && !flush_rrd(i) && !rrd_uops(i).bits.uses_alu) {
        isboard_rrd_clear(rrd_uops(i).bits.rd) := true.B
      }
    } .elsewhen (!ex_stall || flush_ex(i)) {
      ex_uops_reg(i).valid := false.B
    }
  }
  io.imem.redirect_flush := rrd_fire(0) && rrd_uops(0).bits.csr_wen

  // ex


  val fregfile = Reg(Vec(32, UInt(65.W)))
  val fsboard = Reg(Vec(32, Bool()))
  fsboard_bsy := !fsboard.reduce(_&&_)
  val fsboard_wb_set = WireInit(VecInit(0.U(32.W).asBools))
  val fsboard_mem_set = WireInit(VecInit(0.U(32.W).asBools))
  val fsboard_ex_clear = WireInit(VecInit(0.U(32.W).asBools))
  for (i <- 0 until 32) {
    fsboard(i) := (fsboard(i) || fsboard_wb_set(i) || fsboard_mem_set(i)) && !fsboard_ex_clear(i)
  }

  when (reset.asBool) {
    fsboard.foreach(_ := true.B)
  }

  val ex_frs1 = ex_uops_reg(0).bits.rs1
  val ex_fra1 = ex_uops_reg(0).bits.fra1
  val ex_frs2 = ex_uops_reg(0).bits.rs2
  val ex_fra2 = ex_uops_reg(0).bits.fra2
  val ex_frs3 = ex_uops_reg(0).bits.rs3
  val ex_fra3 = ex_uops_reg(0).bits.fra3

  val ex_frs1_data = WireInit(fregfile(ex_fra1))
  val ex_frs2_data = WireInit(fregfile(ex_fra2))
  val ex_frs3_data = WireInit(fregfile(ex_fra3))

  val frs1_older_hazard = !fsboard(ex_frs1)
  val frs2_older_hazard = !fsboard(ex_frs2)
  val frs3_older_hazard = !fsboard(ex_frs3)

  val frs1_data_hazard = !fsboard(ex_frs1) && ex_uops_reg(0).bits.ctrl.rfs1 && ex_uops_reg(0).valid
  val frs2_data_hazard = !fsboard(ex_frs2) && ex_uops_reg(0).bits.ctrl.rfs2 && ex_uops_reg(0).valid
  val frs3_data_hazard = !fsboard(ex_frs3) && ex_uops_reg(0).bits.ctrl.rfs3 && ex_uops_reg(0).valid
  val frd_data_hazard = ex_uops_reg.map(u => {
    val ex_frd = u.bits.rd
    val frd_older_hazard = !fsboard(ex_frd)
    frd_older_hazard && u.valid && u.bits.ctrl.wfd
  }).reduce(_||_)

  val ex_fcsr_data_hazard = ex_uops_reg(0).valid && ex_uops_reg(0).bits.csr_en && !fsboard.reduce(_&&_)

  val ex_fp_data_hazard = frs1_data_hazard || frs2_data_hazard || frs3_data_hazard || frd_data_hazard

  val fp_pipe = Module(new SaturnFPPipe)
  fp_pipe.io.in.valid := ex_uops_reg(0).valid && ex_uops_reg(0).bits.uses_fp && !ex_uops_reg(0).bits.xcpt && !ex_stall
  fp_pipe.io.in.bits := ex_uops_reg(0).bits
  fp_pipe.io.frs1_data := ex_frs1_data
  fp_pipe.io.frs2_data := ex_frs2_data
  fp_pipe.io.frs3_data := ex_frs3_data
  fp_pipe.io.fcsr_rm := csr.io.fcsr_rm
  io.fcsr_rm := csr.io.fcsr_rm

  val mulDivParams = tileParams.core.asInstanceOf[SaturnCoreParams].mulDiv.get
  require(mulDivParams.mulUnroll == 0)

  val mul = Module(new PipelinedMultiplier(64, 2))
  mul.io.req.valid := ex_uops_reg(0).valid && ex_uops_reg(0).bits.ctrl.mul && !ex_stall
  mul.io.req.bits.dw := ex_uops_reg(0).bits.ctrl.alu_dw
  mul.io.req.bits.fn := ex_uops_reg(0).bits.ctrl.alu_fn
  mul.io.req.bits.in1 := ex_uops_reg(0).bits.rs1_data
  mul.io.req.bits.in2 := ex_uops_reg(0).bits.rs2_data
  mul.io.req.bits.tag := ex_uops_reg(0).bits.rd


  val bitmanip = Module(new Bitmanip())
  val imm_bitmanip = ImmGen(ex_uops_reg(0).bits.ctrl.sel_imm, ex_uops_reg(0).bits.inst)
  bitmanip.io.req.valid := ex_uops_reg(0).valid && ex_uops_reg(0).bits.is_bitmanip && !ex_stall && usingBitManip.B
  bitmanip.io.req.bits.dw := ex_uops_reg(0).bits.ctrl.alu_dw
  bitmanip.io.req.bits.fn := Cat(ex_uops_reg(0).bits.ctrl.alu_fn, ex_uops_reg(0).bits.ctrl.mem_cmd(0))
  bitmanip.io.req.bits.in1 := ex_uops_reg(0).bits.rs1_data
  bitmanip.io.req.bits.in2 := Mux(ex_uops_reg(0).bits.ctrl.rxs2 === 1.U,
    ex_uops_reg(0).bits.rs2_data.asUInt, imm_bitmanip.asUInt)


  val ex_dmem_oh = ex_uops_reg.map({u => u.valid && u.bits.ctrl.mem})
  val ex_dmem_uop = Mux1H(ex_dmem_oh, ex_uops_reg)
  val ex_dmem_addrs = Wire(Vec(retireWidth, UInt()))
  io.dmem.req.valid := ex_dmem_oh.reduce(_||_)
  io.dmem.req.bits.tag := Cat(ex_dmem_uop.bits.rd, ex_dmem_uop.bits.ctrl.fp)
  io.dmem.req.bits.cmd := ex_dmem_uop.bits.ctrl.mem_cmd
  io.dmem.req.bits.size := ex_dmem_uop.bits.mem_size
  io.dmem.req.bits.signed := !ex_dmem_uop.bits.inst(14)
  io.dmem.req.bits.phys := false.B
  io.dmem.req.bits.idx.foreach(_ := io.dmem.req.bits.addr)
  io.dmem.req.bits.dprv := csr.io.status.dprv
  io.dmem.req.bits.addr := Mux1H(ex_dmem_oh, ex_dmem_addrs)

  io.dmem.s1_kill := false.B
  io.dmem.s2_kill := false.B

  for (i <- 0 until retireWidth) {
    when (RegNext(io.dmem.req.fire() && ex_dmem_oh(i) && (ex_stall || flush_ex(i)))) {
      io.dmem.s1_kill := true.B
    }
  }

  for (i <- 0 until retireWidth) {
    val alu = Module(new ALU)
    val uop = ex_uops_reg(i).bits
    val ctrl = ex_uops_reg(i).bits.ctrl
    val imm = ImmGen(ctrl.sel_imm, uop.inst)
    val sel_alu1 = WireInit(ctrl.sel_alu1)
    val sel_alu2 = WireInit(ctrl.sel_alu2)
    val ex_op1 = MuxLookup(sel_alu1, 0.S, Seq(
      A1_RS1 -> uop.rs1_data.asSInt,
      A1_PC -> uop.pc.asSInt
    ))
    val ex_op2 = MuxLookup(sel_alu2, 0.S, Seq(
      A2_RS2 -> uop.rs2_data.asSInt,
      A2_IMM -> imm,
      A2_SIZE -> Mux(uop.rvc, 2.S, 4.S)
    ))

    alu.io.dw := ctrl.alu_dw
    alu.io.fn := ctrl.alu_fn
    alu.io.in2 := ex_op2.asUInt
    alu.io.in1 := ex_op1.asUInt

    ex_uops(i).bits.wdata.valid := uop.uses_alu && !uop.uses_memalu
    ex_uops(i).bits.wdata.bits := alu.io.out
    ex_uops(i).bits.taken := alu.io.cmp_out

    ex_bypasses(i).valid := ex_uops_reg(i).valid && ex_uops_reg(i).bits.ctrl.wxd
    ex_bypasses(i).dst := ex_uops_reg(i).bits.rd
    ex_bypasses(i).can_bypass := ex_uops(i).bits.wdata.valid
    ex_bypasses(i).data := alu.io.out

    ex_dmem_addrs(i) := encodeVirtualAddress(uop.rs1_data, alu.io.adder_out)

    when (ctrl.rxs2 && (ctrl.mem || ctrl.rocc || uop.sfence)) {
      val size = Mux(ctrl.rocc, log2Ceil(64/8).U, uop.mem_size)
      ex_uops(i).bits.rs2_data := new StoreGen(size, 0.U, uop.rs2_data, coreDataBytes).data
    }
   }

  val ex_dmem_structural_hazard = io.dmem.req.valid && !io.dmem.req.ready

  ex_stall := ex_uops_reg.map(_.valid).reduce(_||_) && (
    ex_fcsr_data_hazard ||
    ex_dmem_structural_hazard ||
    ex_fp_data_hazard
  )

  for (i <- 0 until retireWidth) {
    when (ex_uops(i).valid && ex_uops(i).bits.ctrl.wxd && flush_ex(i)) {
      isboard_ex_set(ex_uops(i).bits.rd) := true.B
    }
    mem_uops_reg(i) := ex_uops(i)
    mem_uops_reg(i).valid := ex_uops(i).valid && !flush_ex(i) && !ex_stall
    when (ex_uops_reg(i).valid && !ex_stall && ex_uops(i).bits.ctrl.wfd && !flush_ex(i)) {
      fsboard_ex_clear(ex_uops(i).bits.rd) := true.B
    }
  }


  //mem
  fp_pipe.io.s1_kill := kill_mem

  val mem_brjmp_oh = mem_uops_reg.map({u => u.valid && (u.bits.uses_brjmp || u.bits.next_pc.valid)})
  val mem_brjmp_val = mem_brjmp_oh.reduce(_||_)
  val mem_brjmp_uop = Mux1H(mem_brjmp_oh, mem_uops_reg).bits
  val mem_brjmp_ctrl = mem_brjmp_uop.ctrl
  val mem_brjmp_call = (mem_brjmp_ctrl.jalr || mem_brjmp_ctrl.jal) && mem_brjmp_uop.rd === 1.U
  val mem_brjmp_ret = mem_brjmp_ctrl.jalr && mem_brjmp_uop.rs1 === 1.U && mem_brjmp_uop.rd === 0.U
  val mem_brjmp_target = mem_brjmp_uop.pc.asSInt +
    Mux(mem_brjmp_ctrl.branch && mem_brjmp_uop.taken, ImmGen(IMM_SB, mem_brjmp_uop.inst),
    Mux(mem_brjmp_ctrl.jal, ImmGen(IMM_UJ, mem_brjmp_uop.inst),
    Mux(mem_brjmp_uop.rvc, 2.S, 4.S)))
  val mem_brjmp_npc = (Mux(mem_brjmp_ctrl.jalr || mem_brjmp_uop.sfence,
    encodeVirtualAddress(mem_brjmp_uop.wdata.bits, mem_brjmp_uop.wdata.bits).asSInt,
    mem_brjmp_target) & -2.S).asUInt
  val mem_brjmp_wrong_npc = mem_brjmp_uop.next_pc.bits =/= mem_brjmp_npc
  val mem_brjmp_taken = (mem_brjmp_ctrl.branch && mem_brjmp_uop.taken) || mem_brjmp_ctrl.jalr || mem_brjmp_ctrl.jal
  val mem_brjmp_mispredict_taken = mem_brjmp_taken && (!mem_brjmp_uop.next_pc.valid || mem_brjmp_wrong_npc)
  val mem_brjmp_mispredict_not_taken = ((mem_brjmp_ctrl.branch && !mem_brjmp_uop.taken) || !mem_brjmp_uop.cfi) && mem_brjmp_uop.next_pc.valid
  val mem_brjmp_mispredict = mem_brjmp_mispredict_taken || mem_brjmp_mispredict_not_taken

  io.imem.btb_update.valid := mem_brjmp_val
  io.imem.btb_update.bits.mispredict := mem_brjmp_mispredict
  io.imem.btb_update.bits.isValid := mem_brjmp_val
  io.imem.btb_update.bits.cfiType := (
    Mux((mem_brjmp_ctrl.jal || mem_brjmp_ctrl.jalr) && mem_brjmp_uop.rd(0), CFIType.call,
    Mux(mem_brjmp_ctrl.jalr && (mem_brjmp_uop.inst(19,15)) === BitPat("b00?01"), CFIType.ret,
    Mux(mem_brjmp_ctrl.jal || mem_brjmp_ctrl.jalr, CFIType.jump,
    CFIType.branch)))
  )
  val mem_brjmp_bridx = (mem_brjmp_uop.pc >> 1)(log2Ceil(fetchWidth)-1,0)
  val mem_brjmp_is_last_over_edge = mem_brjmp_bridx === (fetchWidth-1).U && !mem_brjmp_uop.rvc
  io.imem.btb_update.bits.target := mem_brjmp_npc
  io.imem.btb_update.bits.br_pc := mem_brjmp_uop.pc + Mux(mem_brjmp_is_last_over_edge, 2.U, 0.U)
  io.imem.btb_update.bits.pc := (~(~io.imem.btb_update.bits.br_pc | (coreInstBytes*fetchWidth-1).U))
  io.imem.btb_update.bits.prediction := mem_brjmp_uop.btb_resp.bits
  when (!mem_brjmp_uop.btb_resp.valid) {
    io.imem.btb_update.bits.prediction.entry := tileParams.btb.get.nEntries.U
  }

  io.imem.bht_update.valid := mem_brjmp_val
  io.imem.bht_update.bits.pc := io.imem.btb_update.bits.pc
  io.imem.bht_update.bits.taken := mem_brjmp_taken
  io.imem.bht_update.bits.mispredict := mem_brjmp_mispredict
  io.imem.bht_update.bits.branch := mem_brjmp_ctrl.branch
  io.imem.bht_update.bits.prediction := mem_brjmp_uop.btb_resp.bits.bht

  io.imem.ras_update.valid := mem_brjmp_call && mem_brjmp_val
  io.imem.ras_update.bits.head := Mux(mem_brjmp_uop.ras_head === (mem_brjmp_uop.nRAS-1).U, 0.U, mem_brjmp_uop.ras_head + 1.U)
  io.imem.ras_update.bits.addr := mem_brjmp_uop.wdata.bits

  when (mem_brjmp_val && mem_brjmp_mispredict) {
    flush_rrd .foreach(_ := true.B)
    flush_ex.foreach(_ := true.B)
    io.imem.redirect_val := true.B
    io.imem.redirect_flush := true.B
    io.imem.redirect_pc := mem_brjmp_npc
    io.imem.redirect_ras_head := Mux(mem_brjmp_call, Mux(mem_brjmp_uop.ras_head === (mem_brjmp_uop.nRAS-1).U, 0.U, mem_brjmp_uop.ras_head + 1.U),
      Mux(mem_brjmp_ret, Mux(mem_brjmp_uop.ras_head === 0.U, (mem_brjmp_uop.nRAS-1).U, mem_brjmp_uop.ras_head - 1.U), mem_brjmp_uop.ras_head))
  }

  for (i <- 0 until retireWidth) {
    val uop = mem_uops_reg(i).bits
    val ctrl = uop.ctrl
    when (mem_uops(i).valid && mem_uops_reg(i).bits.ctrl.jalr && csr.io.status.debug) {
      io.imem.flush_icache := true.B
    }
    when (mem_brjmp_oh(i) && mem_uops_reg(i).bits.ctrl.jalr) {
      mem_uops(i).bits.wdata.valid := true.B
      mem_uops(i).bits.wdata.bits := mem_brjmp_target.asUInt
    }
    when (mem_uops(i).valid && ctrl.mem && isWrite(ctrl.mem_cmd)) {
      io.dmem.s1_data.data := Mux(uop.ctrl.fp, fp_pipe.io.s1_store_data, uop.rs2_data)
    }
    when (mem_uops(i).valid && ctrl.fp && ctrl.wxd) {
      mem_uops(i).bits.wdata.valid := true.B
      mem_uops(i).bits.wdata.bits := fp_pipe.io.s1_fpiu_toint
    }
    mem_uops(i).bits.fexc := fp_pipe.io.s1_fpiu_fexc
    mem_uops(i).bits.fdivin := fp_pipe.io.s1_fpiu_fdiv

    mem_bypasses(i).valid := mem_uops_reg(i).valid && mem_uops_reg(i).bits.ctrl.wxd
    mem_bypasses(i).dst := mem_uops_reg(i).bits.rd
    mem_bypasses(i).can_bypass := mem_uops_reg(i).bits.wdata.valid
    mem_bypasses(i).data := mem_uops_reg(i).bits.wdata.bits
  }

  if (enableMemALU) {
    for (i <- 1 until retireWidth) {
      val alu = Module(new ALU)
      val uop = mem_uops_reg(i).bits
      val ctrl = mem_uops_reg(i).bits.ctrl
      val imm = ImmGen(ctrl.sel_imm, uop.inst)
      val sel_alu1 = WireInit(ctrl.sel_alu1)
      val sel_alu2 = WireInit(ctrl.sel_alu2)
      val rs1_data = Mux(uop.rs1 === mem_uops_reg(0).bits.rd && mem_uops_reg(0).valid && mem_uops_reg(0).bits.ctrl.wxd,
        mem_uops_reg(0).bits.wdata.bits,
        uop.rs1_data)
      val rs2_data = Mux(uop.rs2 === mem_uops_reg(0).bits.rd && mem_uops_reg(0).valid && mem_uops_reg(0).bits.ctrl.wxd,
        mem_uops_reg(0).bits.wdata.bits,
        uop.rs2_data)
      val ex_op1 = MuxLookup(sel_alu1, 0.S, Seq(
        A1_RS1 -> rs1_data.asSInt,
        A1_PC -> uop.pc.asSInt
      ))
      val ex_op2 = MuxLookup(sel_alu2, 0.S, Seq(
        A2_RS2 -> rs2_data.asSInt,
        A2_IMM -> imm,
        A2_SIZE -> Mux(uop.rvc, 2.S, 4.S)
      ))
      alu.io.dw := ctrl.alu_dw
      alu.io.fn := ctrl.alu_fn
      alu.io.in2 := ex_op2.asUInt
      alu.io.in1 := ex_op1.asUInt

      when (uop.uses_memalu) {
        mem_uops(i).bits.wdata.valid := true.B
        mem_uops(i).bits.wdata.bits := alu.io.out
        mem_bypasses(i).valid := mem_uops_reg(i).bits.ctrl.wxd && mem_uops_reg(i).valid
        mem_bypasses(i).can_bypass := true.B
        mem_bypasses(i).data := alu.io.out
      }
    }
  }


  for (i <- 0 until retireWidth) {
    when (RegNext(mem_uops(i).valid && mem_uops(i).bits.ctrl.mem && kill_mem)) {
      io.dmem.s2_kill := true.B
    }
  }

  for (i <- 0 until retireWidth) {
    when (mem_uops(i).valid && mem_uops(i).bits.ctrl.wxd && kill_mem) {
      isboard_mem_set(mem_uops(i).bits.rd) := true.B
    }
    when (mem_uops(i).valid && mem_uops(i).bits.ctrl.wfd && kill_mem) {
      fsboard_mem_set(mem_uops(i).bits.rd) := true.B
    }

    wb_uops_reg(i) := mem_uops(i)
    wb_uops_reg(i).valid := mem_uops_reg(i).valid && !kill_mem
  }

  //wb
  val wb_fp_uop = wb_uops_reg(0)
  val wb_fp_ctrl = wb_fp_uop.bits.fp_ctrl
  val wb_fp_divsqrt = wb_uops_reg(0).valid && wb_fp_uop.bits.uses_fp && (wb_fp_ctrl.div || wb_fp_ctrl.sqrt)

  val divSqrt_val = RegInit(false.B)
  val divSqrt_waddr = Reg(UInt(5.W))
  val divSqrt_typeTag = Reg(UInt(2.W))
  val divSqrt_wdata = Reg(Valid(UInt(65.W)))
  val divSqrt_flags = Reg(UInt(FPConstants.FLAGS_SZ.W))
  when (wb_fp_divsqrt && divSqrt_val) {
    wb_uops(0).bits.needs_replay := true.B
  }
  for (t <- floatTypes) {
    val tag = wb_fp_uop.bits.fp_ctrl.typeTagOut
    val divSqrt = Module(new hardfloat.DivSqrtRecFN_small(t.exp, t.sig, 0))
    divSqrt.io.inValid := wb_uops_reg(0).valid && tag === typeTag(t).U && wb_fp_divsqrt
    divSqrt.io.sqrtOp := wb_fp_ctrl.sqrt
    divSqrt.io.a := maxType.unsafeConvert(wb_fp_uop.bits.fdivin.in1, t)
    divSqrt.io.b := maxType.unsafeConvert(wb_fp_uop.bits.fdivin.in2, t)
    divSqrt.io.roundingMode := wb_fp_uop.bits.fdivin.rm
    divSqrt.io.detectTininess := hardfloat.consts.tininess_afterRounding

    when (divSqrt.io.inValid) {
      assert(divSqrt.io.inReady)
      divSqrt_waddr := wb_fp_uop.bits.rd
      divSqrt_val := true.B
      divSqrt_wdata.valid := false.B
    }

    when (divSqrt.io.outValid_div || divSqrt.io.outValid_sqrt) {
      divSqrt_wdata.valid := true.B
      divSqrt_wdata.bits := box(sanitizeNaN(divSqrt.io.out, t), t)
      divSqrt_flags := divSqrt.io.exceptionFlags
      divSqrt_typeTag := typeTag(t).U
    }
  }

  val div = Module(new MulDiv(mulDivParams, width=64))
  div.io.kill := false.B
  div.io.req.valid := wb_uops_reg(0).valid && wb_uops_reg(0).bits.ctrl.div
  div.io.req.bits.dw := wb_uops_reg(0).bits.ctrl.alu_dw
  div.io.req.bits.fn := wb_uops_reg(0).bits.ctrl.alu_fn
  div.io.req.bits.in1 := wb_uops_reg(0).bits.rs1_data
  div.io.req.bits.in2 := wb_uops_reg(0).bits.rs2_data
  div.io.req.bits.tag := wb_uops_reg(0).bits.rd
  div.io.resp.ready := false.B
  when (wb_uops_reg(0).valid && wb_uops_reg(0).bits.ctrl.div && !div.io.req.ready) {
    wb_uops(0).bits.needs_replay := true.B
  }

  when (wb_uops_reg(0).bits.ctrl.mul) {
    wb_uops(0).bits.wdata.valid := true.B
    wb_uops(0).bits.wdata.bits := mul.io.resp.bits.data
  }

  if (usingBitManip) {
    when (wb_uops_reg(0).bits.is_bitmanip) {
      wb_uops(0).bits.wdata.valid := true.B
      wb_uops(0).bits.wdata.bits := bitmanip.io.resp.bits
    }
  }

  val wb_rocc_valid = wb_uops_reg(0).valid && wb_uops_reg(0).bits.ctrl.rocc
  val wb_rocc_uop = wb_uops_reg(0)
  io.rocc.cmd.valid := false.B
  io.rocc.cmd.bits := DontCare
  if (usingRoCC) {
    io.rocc.cmd.valid := wb_rocc_valid
    io.rocc.cmd.bits.status := csr.io.status
    io.rocc.cmd.bits.inst := wb_rocc_uop.bits.inst.asTypeOf(new RoCCInstruction)
    io.rocc.cmd.bits.rs1 := wb_rocc_uop.bits.rs1_data
    io.rocc.cmd.bits.rs2 := wb_rocc_uop.bits.rs2_data
    assert(!(io.rocc.cmd.valid && !io.rocc.cmd.ready))
  }

  val wb_xcpt_uop = wb_uops(0)
  val wb_xcpt_uop_reg = wb_uops_reg(0)
  csr.io.exception := wb_uops_reg(0).valid && !kill_wb(0) && wb_uops(0).bits.xcpt && !wb_uops(0).bits.needs_replay
  csr.io.cause := wb_xcpt_uop.bits.xcpt_cause
  csr.io.retire := PopCount(kill_wb zip wb_uops map { case (k, u) => u.valid && !k && !u.bits.xcpt && !u.bits.needs_replay })
  debug_irt_reg := debug_irt_reg + csr.io.retire
  csr.io.pc := wb_uops_reg(0).bits.pc
  var found_valid = wb_uops_reg(0).valid
  for (i <- 1 until retireWidth) {
    when (!found_valid) {
      csr.io.pc := wb_uops_reg(i).bits.pc
    }
    found_valid = found_valid || wb_uops_reg(i).valid
  }
  for (i <- 0 until retireWidth) {
    csr.io.inst(i) := wb_uops_reg(i).bits.raw_inst
  }
  csr.io.interrupts := io.interrupts
  csr.io.hartid := io.hartid
  csr.io.rocc_interrupt := io.rocc.interrupt
  val tval_valid = csr.io.exception && csr.io.cause.isOneOf(
    Causes.illegal_instruction.U, Causes.breakpoint.U,
    Causes.misaligned_load.U, Causes.misaligned_store.U,
    Causes.load_access.U, Causes.store_access.U, Causes.fetch_access.U,
    Causes.load_page_fault.U, Causes.store_page_fault.U, Causes.fetch_page_fault.U)
  csr.io.tval := Mux(tval_valid,
    encodeVirtualAddress(wb_xcpt_uop_reg.bits.wdata.bits, wb_xcpt_uop_reg.bits.wdata.bits), 0.U)
  io.ptw.ptbr := csr.io.ptbr
  io.ptw.status := csr.io.status
  io.ptw.pmp := csr.io.pmp
  io.trace := csr.io.trace
  csr.io.rw.addr := wb_uops_reg(0).bits.inst(31,20)
  csr.io.rw.cmd := CSR.maskCmd(wb_uops_reg(0).valid && !wb_uops_reg(0).bits.xcpt,
    wb_uops_reg(0).bits.ctrl.csr)
  csr.io.rw.wdata := wb_uops_reg(0).bits.wdata.bits
  when (wb_uops_reg(0).bits.ctrl.csr =/= CSR.N) {
    wb_uops(0).bits.wdata.valid := true.B
    wb_uops(0).bits.wdata.bits := csr.io.rw.rdata
  }

  when (wb_uops_reg(0).valid && !wb_uops_reg(0).bits.xcpt && !wb_uops_reg(0).bits.needs_replay && wb_uops_reg(0).bits.ctrl.fence_i) {
    io.imem.flush_icache := true.B
  }


  csr.io.fcsr_flags.valid := false.B
  csr.io.set_fs_dirty.map(_ := false.B)
  val csr_fcsr_flags = Wire(Vec(3, UInt(FPConstants.FLAGS_SZ.W)))
  csr_fcsr_flags.foreach(_ := 0.U)
  csr.io.fcsr_flags.bits := csr_fcsr_flags.reduce(_|_)

  val wb_mem = wb_uops_reg(0).valid && wb_uops_reg(0).bits.ctrl.mem
  val (xcpt, cause) = checkExceptions(List(
    (wb_uops_reg(0).bits.xcpt       , wb_uops_reg(0).bits.xcpt_cause),
    (wb_mem && io.dmem.s2_xcpt.ma.st, Causes.misaligned_store.U),
    (wb_mem && io.dmem.s2_xcpt.ma.ld, Causes.misaligned_load.U),
    (wb_mem && io.dmem.s2_xcpt.pf.st, Causes.store_page_fault.U),
    (wb_mem && io.dmem.s2_xcpt.pf.ld, Causes.load_page_fault.U),
    (wb_mem && io.dmem.s2_xcpt.ae.st, Causes.store_access.U),
    (wb_mem && io.dmem.s2_xcpt.ae.ld, Causes.load_access.U)
  ))
  wb_uops(0).bits.xcpt := xcpt
  wb_uops(0).bits.xcpt_cause := cause
  for (i <- 0 until retireWidth) {
    when (wb_uops_reg(i).valid && wb_uops_reg(i).bits.ctrl.mem && io.dmem.s2_nack) {
      wb_uops(i).bits.needs_replay := true.B
    }
    when (wb_uops_reg(i).valid && wb_uops_reg(i).bits.ctrl.rocc && !io.rocc.cmd.ready) {
      wb_uops(i).bits.needs_replay := true.B
    }
  }
  for (i <- 1 until retireWidth) {
    when (wb_uops_reg(i).valid && wb_uops_reg(i).bits.ctrl.mem && io.dmem.s2_xcpt.asUInt =/= 0.U) {
      wb_uops(i).bits.needs_replay := true.B
    }
  }

  val usePipelinePrints = PlusArg("saturn_pipe_prints", width=1, default=0)(0)
  for (i <- 0 until retireWidth) {
    val waddr = WireInit(wb_uops(i).bits.rd)
    val wdata = WireInit(wb_uops(i).bits.wdata.bits)
    val wsboard = WireInit(!wb_uops(i).bits.uses_alu)
    val wen = Wire(Bool())
    wen := (wb_uops(i).valid && !kill_wb(i) && wb_uops(i).bits.ctrl.wxd && !wb_uops(i).bits.xcpt &&
      !wb_uops(i).bits.needs_replay && wb_uops(i).bits.wdata.valid)

    when (wen) {
      iregfile(waddr) := wdata
      when (wsboard) {
        isboard_wb_set(waddr) := true.B
      }
    }

    wb_bypasses(i).valid := wb_uops_reg(i).valid && wb_uops_reg(i).bits.ctrl.wxd
    wb_bypasses(i).dst := wb_uops_reg(i).bits.rd
    wb_bypasses(i).can_bypass := wb_uops(i).bits.wdata.valid
    wb_bypasses(i).data := wb_uops(i).bits.wdata.bits

    val com = wb_uops(i).valid && !kill_wb(i) && !wb_uops(i).bits.xcpt && !wb_uops(i).bits.needs_replay
    when (com || usePipelinePrints) {
      val wfd = wb_uops(i).bits.ctrl.wfd
      val wxd = wb_uops(i).bits.ctrl.wxd

      when (com) {
        printf("%d 0x%x ",
          csr.io.status.prv,
          Sext(wb_uops(i).bits.pc, 64))
      } .otherwise {
        printf("____________________ ")
      }
      when (!usePipelinePrints) {
        when (wb_uops(i).bits.rvc) {
          printf("(0x%x)", wb_uops(i).bits.raw_inst(15,0))
        } .otherwise {
          printf("(0x%x)", wb_uops(i).bits.raw_inst)
        }
        when (wxd && wb_uops(i).bits.rd =/= 0.U && wb_uops(i).bits.wdata.valid) {
          printf(" x%d 0x%x",
            wb_uops(i).bits.rd,
            wb_uops(i).bits.wdata.bits)
        } .elsewhen (wxd && wb_uops(i).bits.rd =/= 0.U && !wb_uops(i).bits.wdata.valid) {
          printf(" x%d p%d 0xXXXXXXXXXXXXXXXX",
            wb_uops(i).bits.rd,
            wb_uops(i).bits.rd)
        } .elsewhen (wfd) {
          printf(" f%d p%d 0xXXXXXXXXXXXXXXXX",
            wb_uops(i).bits.rd,
            wb_uops(i).bits.rd + 32.U)
        }
        printf("\n")
      }
    }
  }
  when (usePipelinePrints) {
    printf("\n")
  }


  when (wb_uops_reg(0).valid && wb_uops_reg(0).bits.uses_fp && wb_fp_uop.bits.fp_ctrl.toint && !wb_fp_uop.bits.xcpt) {
    csr.io.fcsr_flags.valid := true.B
    csr_fcsr_flags(0) := wb_fp_uop.bits.fexc
  }

  io.imem.sfence.valid := false.B
  io.ptw.sfence := io.imem.sfence


  when (wb_uops(0).valid && wb_uops(0).bits.sfence && !wb_uops(0).bits.xcpt && !wb_uops(0).bits.needs_replay) {
    io.imem.sfence.valid := true.B
    io.imem.sfence.bits.rs1 := wb_uops(0).bits.mem_size(0)
    io.imem.sfence.bits.rs2 := wb_uops(0).bits.mem_size(1)
    io.imem.sfence.bits.addr := wb_uops(0).bits.wdata.bits
    io.imem.sfence.bits.asid := wb_uops(0).bits.rs2_data
  }

  val replays = wb_uops.map({u => u.valid && u.bits.needs_replay})
  for (i <- (0 until retireWidth).reverse) {
    when (replays(i)) {
      io.imem.redirect_val := true.B
      io.imem.redirect_flush := true.B
      io.imem.redirect_pc := wb_uops(i).bits.pc
      io.imem.redirect_ras_head := wb_uops(i).bits.ras_head
    }
  }

  when (wb_uops_reg(0).valid && ((wb_uops(0).bits.csr_wen && !wb_uops(0).bits.needs_replay) ||
                                  wb_uops(0).bits.flush_pipe)) {
    kill_mem := true.B
    flush_ex.foreach(_ := true.B)
    flush_rrd .foreach(_ := true.B)
    for (i <- 1 until retireWidth) { kill_wb(i) := true.B }
    io.imem.redirect_val := true.B
    io.imem.redirect_flush := true.B
    io.imem.redirect_pc := wb_uops(0).bits.pc + Mux(wb_uops(0).bits.rvc, 2.U, 4.U)
    io.imem.redirect_ras_head := wb_uops(0).bits.ras_head
  }

  when (wb_uops_reg(0).valid && (wb_uops(0).bits.xcpt || csr.io.eret) && !wb_uops(0).bits.needs_replay) {
    kill_mem := true.B
    flush_ex.foreach(_ := true.B)
    flush_rrd .foreach(_ := true.B)
    for (i <- 1 until retireWidth) { kill_wb(i) := true.B }
    io.imem.redirect_val := true.B
    io.imem.redirect_flush := true.B
    io.imem.redirect_pc := csr.io.evec
      io.imem.redirect_ras_head := wb_uops(0).bits.ras_head
  }

  when (replays.reduce(_||_)) {
    kill_mem := true.B
    flush_ex.foreach(_ := true.B)
    flush_rrd .foreach(_ := true.B)
  }
  for (i <- 0 until retireWidth) {
    val killed_by_older_replay = replays.take(i).orR
    when (killed_by_older_replay) {
      kill_wb(i) := true.B
    }
    when (killed_by_older_replay ||
      (wb_uops_reg(i).valid && !kill_wb(i) && (wb_uops(i).bits.xcpt || wb_uops(i).bits.needs_replay))) {
      when (wb_uops(i).valid && wb_uops(i).bits.ctrl.wxd) {
        isboard_wb_set(wb_uops(i).bits.rd) := true.B
      }
      when (wb_uops(i).valid && wb_uops(i).bits.ctrl.wfd) {
        fsboard_wb_set(wb_uops(i).bits.rd) := true.B
      }
    }
  }

  // ll wb
  val dmem_xpu = !io.dmem.resp.bits.tag(0)
  val dmem_fpu =  io.dmem.resp.bits.tag(0)
  val dmem_waddr = io.dmem.resp.bits.tag(5,1)
  val dmem_wdata = io.dmem.resp.bits.data

  class LLWB extends Bundle {
    val waddr = UInt(5.W)
    val wdata = UInt(64.W)
  }

  // dmem, div, rocc
  val ll_arb = Module(new Arbiter(new LLWB, 3))
  ll_arb.io.out.ready := true.B

  ll_arb.io.in(0).valid := io.dmem.resp.valid && io.dmem.resp.bits.has_data && dmem_xpu
  ll_arb.io.in(0).bits.waddr := dmem_waddr
  ll_arb.io.in(0).bits.wdata := dmem_wdata

  ll_arb.io.in(1).valid := div.io.resp.valid
  div.io.resp.ready := ll_arb.io.in(1).ready
  ll_arb.io.in(1).bits.waddr := div.io.resp.bits.tag
  ll_arb.io.in(1).bits.wdata := div.io.resp.bits.data

  ll_arb.io.in(2).valid := io.rocc.resp.valid
  io.rocc.resp.ready := ll_arb.io.in(2).ready
  ll_arb.io.in(2).bits.waddr := io.rocc.resp.bits.rd
  ll_arb.io.in(2).bits.wdata := io.rocc.resp.bits.data

  val ll_wen = ll_arb.io.out.valid
  val ll_waddr = ll_arb.io.out.bits.waddr
  val ll_wdata = ll_arb.io.out.bits.wdata

  ll_bypass(0).valid := ll_wen
  ll_bypass(0).dst := ll_waddr
  ll_bypass(0).data := ll_wdata
  ll_bypass(0).can_bypass := true.B

  when (ll_wen) {
    iregfile(ll_waddr) := ll_wdata
    isboard_wb_set(ll_waddr) := true.B
    printf("x%d p%d 0x%x\n", ll_waddr, ll_waddr, ll_wdata)
  }


  val fp_load_val = RegNext(io.dmem.resp.valid && io.dmem.resp.bits.has_data && dmem_fpu)
  val fp_load_type = RegNext(io.dmem.resp.bits.size - typeTagWbOffset)
  val fp_load_addr = RegNext(io.dmem.resp.bits.tag(5,1))
  val fp_load_data = RegNext(io.dmem.resp.bits.data)

  val ll_fp_wval = WireInit(fp_load_val)
  val ll_fp_wdata = WireInit(0.U(65.W))
  val ll_fp_waddr = WireInit(fp_load_addr)
  when (fp_load_val) {
    ll_fp_wval := true.B
    ll_fp_wdata := recode(fp_load_data, fp_load_type)
    ll_fp_waddr := fp_load_addr
  } .elsewhen (divSqrt_val && divSqrt_wdata.valid) {
    ll_fp_wval := true.B
    ll_fp_wdata := divSqrt_wdata.bits
    ll_fp_waddr := divSqrt_waddr
    divSqrt_val := false.B
    csr.io.fcsr_flags.valid := true.B
    csr_fcsr_flags(1) := divSqrt_flags
  }

  when (ll_fp_wval) {
    printf("f%d p%d 0x%x\n", ll_fp_waddr, ll_fp_waddr + 32.U, ieee(ll_fp_wdata))
    fregfile(ll_fp_waddr) := ll_fp_wdata
    csr.io.set_fs_dirty.map(_ := true.B)
    fsboard_wb_set(ll_fp_waddr) := true.B
  }

  when (fp_pipe.io.out.valid) {
    val wdata = box(fp_pipe.io.out.bits.data, fp_pipe.io.out_tag)
    val ieee_wdata = ieee(wdata)
    val waddr = fp_pipe.io.out_rd
    printf("f%d p%d 0x%x\n", waddr, waddr + 32.U, ieee_wdata)
    fregfile(waddr) := wdata
    fsboard_wb_set(waddr) := true.B
    csr.io.fcsr_flags.valid := true.B
    csr.io.set_fs_dirty.foreach(_ := true.B)
    csr_fcsr_flags(2) := fp_pipe.io.out.bits.exc
  }

  for (i <- 0 until retireWidth) {
    when (reset.asBool) {
      ex_uops_reg(i).valid := false.B
      mem_uops_reg(i).valid := false.B
      wb_uops_reg(i).valid := false.B
    }
  }
}
