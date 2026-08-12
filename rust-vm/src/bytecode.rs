//! Decodes a canon-text `Program` artifact (produced by the Meta-level
//! compiler, features/rustvm/compile.meta's `CompileRustSource`) into a
//! native, directly-executable form.
//!
//! Known Phase 1 simplification: instructions are decoded into a tagged Rust
//! `enum` per function, not yet a flat fixed-width encoding (e.g. 4xu32 per
//! instruction). This crate's hot loop still benefits from the register-file
//! and arena work below; packing the instruction stream itself into cache
//! lines is a named follow-up, not attempted in this pass.

use crate::canon::Value as Canon;

#[derive(Debug, Clone, Copy)]
pub enum IntOp {
    Add,
    Sub,
    Mul,
    Div,
    Mod,
}

#[derive(Debug, Clone, Copy)]
pub enum CmpOp {
    Eq,
    Ne,
    Lt,
    Le,
    Gt,
    Ge,
}

#[derive(Debug, Clone)]
pub enum Instruction {
    LoadConst { dst: usize, value: RtConst },
    Move { dst: usize, src: usize },
    IntBinOp { dst: usize, op: IntOp, left: usize, right: usize },
    IntCmp { dst: usize, op: CmpOp, left: usize, right: usize },
    BoolNot { dst: usize, src: usize },
    Jump { target: usize },
    JumpIfFalse { cond: usize, target: usize },
    Call { dst: usize, function: String, args: Vec<usize> },
    Return { src: usize },
    NewStruct { dst: usize, fields: Vec<usize> },
    GetField { dst: usize, base: usize, field_index: usize },
    NewEnum { dst: usize, tag: String, fields: Vec<usize> },
    TagEq { dst: usize, value: usize, tag: String },
    EnumField { dst: usize, value: usize, field_index: usize },
    MakeArray { dst: usize, items: Vec<usize> },
    ArrayGet { dst: usize, array: usize, index: usize },
    ArrayLen { dst: usize, array: usize },
}

#[derive(Debug, Clone, Copy)]
pub enum RtConst {
    Int(i64),
    Bool(bool),
}

pub struct Function {
    pub name: String,
    pub register_count: usize,
    pub code: Vec<Instruction>,
}

pub struct Program {
    pub functions: Vec<Function>,
    pub entry: String,
}

fn name_of(value: &Canon) -> Result<String, String> {
    match value {
        Canon::Str(s) => Ok(s.clone()),
        Canon::Sym(s) => Ok(s.clone()),
        other => Err(format!("expected a name, found {other:?}")),
    }
}

fn nat(value: &Canon) -> Result<usize, String> {
    Ok(value.as_nat()? as usize)
}

fn nat_list(value: &Canon) -> Result<Vec<usize>, String> {
    value.as_list()?.iter().map(nat).collect()
}

fn int_op(sym: &str) -> Result<IntOp, String> {
    match sym {
        "add" => Ok(IntOp::Add),
        "sub" => Ok(IntOp::Sub),
        "mul" => Ok(IntOp::Mul),
        "div" => Ok(IntOp::Div),
        "mod" => Ok(IntOp::Mod),
        other => Err(format!("unknown int op {other}")),
    }
}

fn cmp_op(sym: &str) -> Result<CmpOp, String> {
    match sym {
        "eq" => Ok(CmpOp::Eq),
        "ne" => Ok(CmpOp::Ne),
        "lt" => Ok(CmpOp::Lt),
        "le" => Ok(CmpOp::Le),
        "gt" => Ok(CmpOp::Gt),
        "ge" => Ok(CmpOp::Ge),
        other => Err(format!("unknown cmp op {other}")),
    }
}

fn decode_const(value: &Canon) -> Result<RtConst, String> {
    match value {
        Canon::Nat(n) => Ok(RtConst::Int(*n)),
        Canon::Sym(s) if s == "t" => Ok(RtConst::Bool(true)),
        Canon::Sym(s) if s == "f" => Ok(RtConst::Bool(false)),
        other => Err(format!("unsupported constant {other:?}")),
    }
}

fn decode_instruction(value: &Canon) -> Result<Instruction, String> {
    let (tag, args) = value.as_node()?;
    Ok(match (tag, args) {
        ("LoadConst", [dst, value]) => Instruction::LoadConst { dst: nat(dst)?, value: decode_const(value)? },
        ("Move", [dst, src]) => Instruction::Move { dst: nat(dst)?, src: nat(src)? },
        ("IntBinOp", [dst, op, left, right]) => Instruction::IntBinOp {
            dst: nat(dst)?,
            op: int_op(op.as_sym()?)?,
            left: nat(left)?,
            right: nat(right)?,
        },
        ("IntCmp", [dst, op, left, right]) => Instruction::IntCmp {
            dst: nat(dst)?,
            op: cmp_op(op.as_sym()?)?,
            left: nat(left)?,
            right: nat(right)?,
        },
        ("BoolNot", [dst, src]) => Instruction::BoolNot { dst: nat(dst)?, src: nat(src)? },
        ("Jump", [target]) => Instruction::Jump { target: nat(target)? },
        ("JumpIfFalse", [cond, target]) => Instruction::JumpIfFalse { cond: nat(cond)?, target: nat(target)? },
        ("Call", [dst, function, args]) => {
            Instruction::Call { dst: nat(dst)?, function: name_of(function)?, args: nat_list(args)? }
        }
        ("Return", [src]) => Instruction::Return { src: nat(src)? },
        ("NewStruct", [dst, fields]) => Instruction::NewStruct { dst: nat(dst)?, fields: nat_list(fields)? },
        ("GetField", [dst, base, field_index]) => {
            Instruction::GetField { dst: nat(dst)?, base: nat(base)?, field_index: nat(field_index)? }
        }
        ("NewEnum", [dst, tag, fields]) => {
            Instruction::NewEnum { dst: nat(dst)?, tag: name_of(tag)?, fields: nat_list(fields)? }
        }
        ("TagEq", [dst, value, tag]) => Instruction::TagEq { dst: nat(dst)?, value: nat(value)?, tag: name_of(tag)? },
        ("EnumField", [dst, value, field_index]) => {
            Instruction::EnumField { dst: nat(dst)?, value: nat(value)?, field_index: nat(field_index)? }
        }
        ("MakeArray", [dst, items]) => Instruction::MakeArray { dst: nat(dst)?, items: nat_list(items)? },
        ("ArrayGet", [dst, array, index]) => {
            Instruction::ArrayGet { dst: nat(dst)?, array: nat(array)?, index: nat(index)? }
        }
        ("ArrayLen", [dst, array]) => Instruction::ArrayLen { dst: nat(dst)?, array: nat(array)? },
        (other, args) => return Err(format!("unknown instruction {other} with {} args", args.len())),
    })
}

fn decode_function(value: &Canon) -> Result<Function, String> {
    let (tag, args) = value.as_node()?;
    if tag != "Function" && tag != "function" {
        return Err(format!("expected a Function node, found {tag}"));
    }
    let [name, _arity, register_count, code] = args else {
        return Err(format!("Function node has {} args, expected 4", args.len()));
    };
    let instructions = code.as_list()?.iter().map(decode_instruction).collect::<Result<Vec<_>, _>>()?;
    Ok(Function { name: name_of(name)?, register_count: nat(register_count)?, code: instructions })
}

pub fn decode_program(value: &Canon) -> Result<Program, String> {
    let (tag, args) = value.as_node()?;
    if tag != "Program" && tag != "program" {
        return Err(format!("expected a Program node, found {tag}"));
    }
    let [functions, entry] = args else {
        return Err(format!("Program node has {} args, expected 2", args.len()));
    };
    let functions = functions.as_list()?.iter().map(decode_function).collect::<Result<Vec<_>, _>>()?;
    Ok(Program { functions, entry: name_of(entry)? })
}
