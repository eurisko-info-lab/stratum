//! The interpreter loop: structurally mirrors
//! features/rustvm/rustvm.meta's `RunFunction`/`StepInstruction`
//! instruction-for-instruction, so a mismatch between this crate and the
//! Meta reference on a given fixture maps to one identifiable instruction on
//! both sides.
//!
//! The register file is a flat `Vec<Value>` allocated once per call with its
//! final size known up front (`Function::register_count`), never resized
//! during execution -- the concrete native artifact of the Meta-level
//! "contiguous per-call frame" design. Struct/enum/array payloads live in one
//! shared `Arena` (see value.rs), addressed by index rather than by pointer
//! or `Rc`.

use crate::bytecode::{CmpOp, Function, Instruction, IntOp, Program, RtConst};
use crate::value::{Arena, Value};

pub struct Interpreter<'p> {
    program: &'p Program,
    arena: Arena,
}

impl<'p> Interpreter<'p> {
    pub fn new(program: &'p Program) -> Self {
        Interpreter { program, arena: Arena::new() }
    }

    pub fn run(&mut self) -> Result<Value, String> {
        let entry = self.find_function(&self.program.entry.clone())?;
        self.call(entry, Vec::new())
    }

    fn find_function(&self, name: &str) -> Result<&'p Function, String> {
        self.program
            .functions
            .iter()
            .find(|f| f.name == name)
            .ok_or_else(|| format!("unknown function {name}"))
    }

    fn call(&mut self, function: &'p Function, args: Vec<Value>) -> Result<Value, String> {
        let mut registers = vec![Value::Unit; function.register_count];
        for (i, value) in args.into_iter().enumerate() {
            registers[i] = value;
        }
        let mut pc = 0usize;
        loop {
            let instr = function
                .code
                .get(pc)
                .ok_or_else(|| format!("program counter {pc} out of bounds in {}", function.name))?;
            if let Instruction::Return { src } = instr {
                return Ok(registers[*src]);
            }
            pc = self.step(&mut registers, pc, instr)?;
        }
    }

    fn step(&mut self, registers: &mut [Value], pc: usize, instr: &Instruction) -> Result<usize, String> {
        match instr {
            Instruction::LoadConst { dst, value } => {
                registers[*dst] = match value {
                    RtConst::Int(n) => Value::Int(*n),
                    RtConst::Bool(b) => Value::Bool(*b),
                };
                Ok(pc + 1)
            }
            Instruction::Move { dst, src } => {
                registers[*dst] = registers[*src];
                Ok(pc + 1)
            }
            Instruction::IntBinOp { dst, op, left, right } => {
                let l = as_int(registers[*left])?;
                let r = as_int(registers[*right])?;
                let result = match op {
                    IntOp::Add => l + r,
                    IntOp::Sub => l - r,
                    IntOp::Mul => l * r,
                    IntOp::Div => l / r,
                    IntOp::Mod => l % r,
                };
                registers[*dst] = Value::Int(result);
                Ok(pc + 1)
            }
            Instruction::IntCmp { dst, op, left, right } => {
                let l = as_int(registers[*left])?;
                let r = as_int(registers[*right])?;
                let result = match op {
                    CmpOp::Eq => l == r,
                    CmpOp::Ne => l != r,
                    CmpOp::Lt => l < r,
                    CmpOp::Le => l <= r,
                    CmpOp::Gt => l > r,
                    CmpOp::Ge => l >= r,
                };
                registers[*dst] = Value::Bool(result);
                Ok(pc + 1)
            }
            Instruction::BoolNot { dst, src } => {
                registers[*dst] = Value::Bool(!as_bool(registers[*src])?);
                Ok(pc + 1)
            }
            Instruction::Jump { target } => Ok(*target),
            Instruction::JumpIfFalse { cond, target } => {
                if as_bool(registers[*cond])? {
                    Ok(pc + 1)
                } else {
                    Ok(*target)
                }
            }
            Instruction::Call { dst, function: callee_name, args } => {
                let callee = self.find_function(callee_name)?;
                let arg_values: Vec<Value> = args.iter().map(|r| registers[*r]).collect();
                let result = self.call(callee, arg_values)?;
                registers[*dst] = result;
                Ok(pc + 1)
            }
            Instruction::Return { .. } => unreachable!("Return handled by the caller loop"),
            Instruction::NewStruct { dst, fields } => {
                let values: Vec<Value> = fields.iter().map(|r| registers[*r]).collect();
                registers[*dst] = Value::Heap(self.arena.alloc(None, values));
                Ok(pc + 1)
            }
            Instruction::GetField { dst, base, field_index } => {
                let offset = as_heap(registers[*base])?;
                registers[*dst] = self.arena.get(offset).fields[*field_index];
                Ok(pc + 1)
            }
            Instruction::NewEnum { dst, tag, fields } => {
                let values: Vec<Value> = fields.iter().map(|r| registers[*r]).collect();
                registers[*dst] = Value::Heap(self.arena.alloc(Some(tag.clone()), values));
                Ok(pc + 1)
            }
            Instruction::TagEq { dst, value, tag } => {
                let offset = as_heap(registers[*value])?;
                let actual = self.arena.get(offset).tag.as_deref().unwrap_or("");
                registers[*dst] = Value::Bool(actual == tag);
                Ok(pc + 1)
            }
            Instruction::EnumField { dst, value, field_index } => {
                let offset = as_heap(registers[*value])?;
                registers[*dst] = self.arena.get(offset).fields[*field_index];
                Ok(pc + 1)
            }
            Instruction::MakeArray { dst, items } => {
                let values: Vec<Value> = items.iter().map(|r| registers[*r]).collect();
                registers[*dst] = Value::Heap(self.arena.alloc(None, values));
                Ok(pc + 1)
            }
            Instruction::ArrayGet { dst, array, index } => {
                let offset = as_heap(registers[*array])?;
                let i = as_int(registers[*index])? as usize;
                registers[*dst] = self.arena.get(offset).fields[i];
                Ok(pc + 1)
            }
            Instruction::ArrayLen { dst, array } => {
                let offset = as_heap(registers[*array])?;
                registers[*dst] = Value::Int(self.arena.get(offset).fields.len() as i64);
                Ok(pc + 1)
            }
        }
    }
}

fn as_int(v: Value) -> Result<i64, String> {
    match v {
        Value::Int(n) => Ok(n),
        other => Err(format!("expected an int register, found {other:?}")),
    }
}

fn as_bool(v: Value) -> Result<bool, String> {
    match v {
        Value::Bool(b) => Ok(b),
        other => Err(format!("expected a bool register, found {other:?}")),
    }
}

fn as_heap(v: Value) -> Result<u32, String> {
    match v {
        Value::Heap(offset) => Ok(offset),
        other => Err(format!("expected a heap register, found {other:?}")),
    }
}
