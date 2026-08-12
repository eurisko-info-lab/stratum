//! A cache-conscious native interpreter for the RustVM bytecode target
//! (features/rustvm/rustvm.meta), validated against that Meta-level
//! reference interpreter by tools/rustvm-check.sh over fixtures/rustvm/*.rs.
//!
//! This crate does not compile Rust source itself -- see rust-vm/README.md
//! and the project plan for why keeping exactly one compiler implementation
//! (the Meta-level one) matters. It only runs a canon-text `Program`
//! artifact that compiler already produced.

mod bytecode;
mod canon;
mod interp;
mod value;

use std::env;
use std::fs;
use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = env::args().collect();
    match run(&args) {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("error: {message}");
            ExitCode::FAILURE
        }
    }
}

fn run(args: &[String]) -> Result<(), String> {
    match args.get(1).map(String::as_str) {
        Some("run") => run_command(args.get(2).ok_or("usage: stratum-rust-vm run <program.canon>")?),
        Some(other) => Err(format!("unknown command '{other}' (expected 'run')")),
        None => Err("usage: stratum-rust-vm run <program.canon>".to_string()),
    }
}

fn run_command(path: &str) -> Result<(), String> {
    let text = fs::read_to_string(path).map_err(|e| format!("reading {path}: {e}"))?;
    let parsed = canon::read(&text)?;
    let program = bytecode::decode_program(&parsed)?;
    let mut interpreter = interp::Interpreter::new(&program);
    let result = interpreter.run()?;
    match result {
        value::Value::Int(n) => println!("{n}"),
        value::Value::Bool(b) => println!("{}", if b { "#t" } else { "#f" }),
        value::Value::Unit => println!("#unit"),
        value::Value::Heap(offset) => println!("<heap {offset}>"),
    }
    Ok(())
}
