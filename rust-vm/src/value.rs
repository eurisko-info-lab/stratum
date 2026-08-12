//! Runtime values and the heap arena.
//!
//! `Value` is sized to fit in one machine word plus a discriminant (no
//! boxing for the common Int/Bool/Unit cases). Struct/enum/array payloads
//! live in `Arena`, addressed by `u32` index rather than a pointer or an
//! `Rc` -- the concrete difference from `host-rust`'s own `Rc`-heavy style,
//! justified by a different workload (host-rust decodes and verifies a value
//! once; this VM re-executes the same instruction stream in a loop, where a
//! pointer/refcount indirection on every field access is the cost that
//! matters).
//!
//! Known Phase 1 simplification, stated plainly rather than left implicit:
//! each heap object is a separate `Vec<Value>` inside one `Vec<HeapObject>`,
//! not yet a single flattened byte buffer with a separate offset/length
//! table. The index-not-pointer discipline (the actual win over `Rc`) is
//! already in place; a fully flattened single-buffer arena is the natural
//! next step, not attempted in this pass.

#[derive(Debug, Clone, Copy)]
pub enum Value {
    Int(i64),
    Bool(bool),
    Unit,
    Heap(u32),
}

pub struct HeapObject {
    pub tag: Option<String>,
    pub fields: Vec<Value>,
}

/// A bump allocator: objects are only ever appended, never freed or mutated
/// in place, matching the Meta reference's own "immutable once built" scope
/// for structs/arrays/enums in Phase 1. Scoped to one `run` invocation --
/// real drop semantics are out of scope until the language subset needs them.
pub struct Arena {
    objects: Vec<HeapObject>,
}

impl Arena {
    pub fn new() -> Self {
        Arena { objects: Vec::new() }
    }

    pub fn alloc(&mut self, tag: Option<String>, fields: Vec<Value>) -> u32 {
        let index = self.objects.len() as u32;
        self.objects.push(HeapObject { tag, fields });
        index
    }

    pub fn get(&self, index: u32) -> &HeapObject {
        &self.objects[index as usize]
    }
}
