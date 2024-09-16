package markorv

import chisel3._
import chisel3.util._

import markorv.DecoderOutParams

class LoadStoreUnit(data_width: Int = 64, addr_width: Int = 64) extends Module {
    val io = IO(new Bundle {
        // lsu_opcode encoding:
        // Bit [3]
        //             0 = Load
        //             1 = Store
        //
        // Bit [2]   - Flag: Load from memory or Immediate
        //             0 = Memory(base address is params.source1 offset is immediate)
        //             1 = Immediate
        //
        // Bit [1]   - Sign: Indicates if the data is signed or unsigned.
        //             0 = Signed integer (SInt)
        //             1 = Unsigned integer (UInt)
        //
        // Bits [0:0] - Size: Specifies the size of the data being loaded or stored.
        //             00 = Byte (8 bits)
        //             01 = Halfword (16 bits)
        //             10 = Word (32 bits)
        //             11 = Doubleword (64 bits)
        val lsu_instr = Flipped(Decoupled(new Bundle {
            val lsu_opcode = UInt(5.W)
            val params = new DecoderOutParams(data_width)
        }))

        val mem_write = Decoupled(UInt(data_width.W))
        val mem_read = Flipped(Decoupled((UInt(data_width.W))))
        val mem_addr = Output(UInt(addr_width.W))

        val write_back = Decoupled(new Bundle {
            val write_back_enable = Output(Bool())
            val write_back_data = Output(UInt(data_width.W))
            val write_register = Output(UInt(5.W))
        })
    })

    // State def
    object State extends ChiselEnum {
        val stat_idle, stat_load, stat_store, stat_writeback = Value
    }
    val state = RegInit(State.stat_idle)

    // Register def
    val opcode = Reg(UInt(5.W))
    val params = Reg(new DecoderOutParams(data_width))
    val load_data = Reg(UInt(data_width.W))

    // default
    io.mem_addr := 0.U(addr_width.W)
    io.mem_write.bits := 0.U(data_width.W)
    io.mem_write.valid := false.B
    io.lsu_instr.ready := false.B
    io.mem_read.ready := false.B

    io.write_back.bits.write_back_enable := false.B
    io.write_back.bits.write_back_data := 0.U(data_width.W)
    io.write_back.bits.write_register := 0.U(5.W)

    switch(state) {
        is(State.stat_idle) {
            io.lsu_instr.ready := true.B
            when(io.lsu_instr.fire) {
                opcode := io.lsu_instr.bits.lsu_opcode
                params := io.lsu_instr.bits.params
                state := Mux(io.lsu_instr.bits.lsu_opcode(3), State.stat_store, State.stat_load)
            }
        }

        is(State.stat_load) {
            val is_immediate = opcode(2)
            val is_signed = !opcode(1)
            val size = opcode(1, 0)

            when(is_immediate) {
                load_data := params.immediate.asUInt
                state := State.stat_writeback
            }.otherwise {
                io.mem_addr := (params.source1.asUInt + params.immediate.asUInt)
                io.mem_read.ready := true.B
                when(io.mem_read.valid) {
                    load_data := io.mem_read.bits
                    // TODO
                    state := State.stat_writeback
                }
            }
        }

        is(State.stat_store) {
            io.mem_addr := (params.source1.asUInt + params.immediate.asUInt)
            io.mem_write.bits := params.source2.asUInt
            io.mem_write.valid := true.B
            when(io.mem_write.valid) {
                state := State.stat_idle
            }
        }

        is(State.stat_writeback) {
            io.write_back.bits.write_back_enable := true.B
            io.write_back.bits.write_back_data := load_data
            io.write_back.bits.write_register := params.rd
            io.write_back.valid := true.B
            when(io.write_back.valid) {
                state := State.stat_idle
            }
        }
    }
}