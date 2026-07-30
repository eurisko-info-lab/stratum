/-
  A model of the actual Meta0 program language and its evaluator.

  Unlike an abstract expression calculus, this file models what the hosts really
  interpret: canonical values, the Meta0 expression and pattern forms carried as
  canonical data, an environment, a judgment table, an explicit step budget and
  a canonical verdict.

  Scope. The model covers the value universe, the expression forms `q`, `v`,
  `mk`, `lst`, `if`, `let`, `call`, `match`, `prim` and `fail`, the pattern
  forms `_`, `pv`, `pq`, `pm`, `pl`, `pcons` and `pnil`, and the structural
  primitives. Capability requests are deliberately excluded: they are the one
  place where the machine talks to the outside world, and they are constituted
  by the Kernel rather than by the calculus.
-/

namespace Stratum

/-- The canonical value universe. -/
inductive Canon where
  | unit : Canon
  | bool : Bool → Canon
  | nat : Nat → Canon
  | str : String → Canon
  | sym : String → Canon
  | list : List Canon → Canon
  | node : String → List Canon → Canon
  deriving Repr, Inhabited

mutual

/-- Structural equality on canonical values. -/
def Canon.beq : Canon → Canon → Bool
  | .unit, .unit => true
  | .bool a, .bool b => a == b
  | .nat a, .nat b => a == b
  | .str a, .str b => a == b
  | .sym a, .sym b => a == b
  | .list a, .list b => Canon.beqAll a b
  | .node ta a, .node tb b => ta == tb && Canon.beqAll a b
  | _, _ => false

def Canon.beqAll : List Canon → List Canon → Bool
  | [], [] => true
  | a :: as, b :: bs => Canon.beq a b && Canon.beqAll as bs
  | _, _ => false

end

instance : BEq Canon := ⟨Canon.beq⟩

/-- A canonical verdict. Budget exhaustion is a value, never a failure. -/
inductive Verdict where
  | ok : Canon → Nat → Verdict
  | err : String → Nat → Verdict
  deriving Repr

/-- An environment binds parameter names to canonical values. -/
abbrev Env := List (String × Canon)

def lookup : Env → String → Option Canon
  | [], _ => none
  | (key, value) :: rest, name => if key == name then some value else lookup rest name

/-- A judgment is a parameter list and a body expression carried as data. -/
abbrev Judgment := List String × Canon

abbrev Program := List (String × Judgment)

def judgmentOf : Program → String → Option Judgment
  | [], _ => none
  | (key, value) :: rest, name => if key == name then some value else judgmentOf rest name

def bindAll : List String → List Canon → Option Env
  | [], [] => some []
  | name :: names, value :: values => (bindAll names values).map (fun rest => (name, value) :: rest)
  | _, _ => none

/-- The fixed structural primitives modelled here. -/
def prim : String → List Canon → Option Canon
  | "eq", [a, b] => some (.bool (a == b))
  | "tag", [.node t _] => some (.sym t)
  | "args", [.node _ a] => some (.list a)
  | "len", [.list items] => some (.nat items.length)
  | "cons", [head, .list tail] => some (.list (head :: tail))
  | "head", [.list (head :: _)] => some head
  | "tail", [.list (_ :: tail)] => some (.list tail)
  | "add", [.nat a, .nat b] => some (.nat (a + b))
  | "sub", [.nat a, .nat b] => some (.nat (a - b))
  | "lt", [.nat a, .nat b] => some (.bool (a < b))
  | "not", [.bool b] => some (.bool (!b))
  | _, _ => none

mutual

/-- Pattern matching extends the environment or fails. -/
def matchPattern : Canon → Canon → Env → Option Env
  | .sym "_", _, env => some env
  | .node "pv" [.sym name], value, env => some ((name, value) :: env)
  | .node "pq" [expected], value, env => if expected == value then some env else none
  | .node "pnil" [], .list [], env => some env
  | .node "pcons" [headPattern, tailPattern], .list (head :: tail), env =>
      match matchPattern headPattern head env with
      | none => none
      | some next => matchPattern tailPattern (.list tail) next
  | .node "pl" patterns, .list values, env => matchAll patterns values env
  | .node "pm" (.sym expected :: patterns), .node actual values, env =>
      if expected == actual then matchAll patterns values env else none
  | _, _, _ => none

def matchAll : List Canon → List Canon → Env → Option Env
  | [], [], env => some env
  | pattern :: patterns, value :: values, env =>
      match matchPattern pattern value env with
      | none => none
      | some next => matchAll patterns values next
  | _, _, _ => none

end

mutual

/--
  `eval fuel program env expression` is the modelled derivation relation.

  Every recursive call consumes fuel or shortens a list, so the evaluator is
  total and the step count is a function of the inputs.
-/
def eval (fuel : Nat) (p : Program) (env : Env) (e : Canon) : Verdict :=
  match fuel with
  | 0 => .err "resource-exhausted" 0
  | fuel + 1 =>
    match e with
    | .node "q" [value] => .ok value 1
    | .node "v" [.sym name] =>
        match lookup env name with
        | some value => .ok value 1
        | none => .err "unbound-variable" 1
    | .node "mk" (.sym tag :: arguments) =>
        match evalAll fuel p env arguments with
        | .inl (values, steps) => .ok (.node tag values) (steps + 1)
        | .inr (kind, steps) => .err kind (steps + 1)
    | .node "lst" arguments =>
        match evalAll fuel p env arguments with
        | .inl (values, steps) => .ok (.list values) (steps + 1)
        | .inr (kind, steps) => .err kind (steps + 1)
    | .node "if" [condition, consequent, alternative] =>
        match eval fuel p env condition with
        | .err kind steps => .err kind (steps + 1)
        | .ok (.bool true) steps =>
            match eval fuel p env consequent with
            | .ok value inner => .ok value (steps + inner + 1)
            | .err kind inner => .err kind (steps + inner + 1)
        | .ok (.bool false) steps =>
            match eval fuel p env alternative with
            | .ok value inner => .ok value (steps + inner + 1)
            | .err kind inner => .err kind (steps + inner + 1)
        | .ok _ steps => .err "type-error" (steps + 1)
    | .node "let" [.sym name, value, body] =>
        match eval fuel p env value with
        | .err kind steps => .err kind (steps + 1)
        | .ok bound steps =>
            match eval fuel p ((name, bound) :: env) body with
            | .ok result inner => .ok result (steps + inner + 1)
            | .err kind inner => .err kind (steps + inner + 1)
    | .node "call" (.sym name :: arguments) =>
        match judgmentOf p name with
        | none => .err "unknown-judgment" 1
        | some (parameters, body) =>
            match evalAll fuel p env arguments with
            | .inr (kind, steps) => .err kind (steps + 1)
            | .inl (values, steps) =>
                match bindAll parameters values with
                | none => .err "arity-error" (steps + 1)
                | some frame =>
                    match eval fuel p frame body with
                    | .ok result inner => .ok result (steps + inner + 1)
                    | .err kind inner => .err kind (steps + inner + 1)
    | .node "prim" (.sym name :: arguments) =>
        match evalAll fuel p env arguments with
        | .inr (kind, steps) => .err kind (steps + 1)
        | .inl (values, steps) =>
            match prim name values with
            | some value => .ok value (steps + 1)
            | none => .err "unknown-primitive" (steps + 1)
    | .node "match" (scrutinee :: cases) =>
        match eval fuel p env scrutinee with
        | .err kind steps => .err kind (steps + 1)
        | .ok value steps =>
            match evalCases fuel p env value cases with
            | .ok result inner => .ok result (steps + inner + 1)
            | .err kind inner => .err kind (steps + inner + 1)
    | .node "fail" [.sym kind, _] => .err kind 1
    | _ => .err "bad-expression" 1
termination_by (fuel, 0, 0)

/-- Evaluates arguments left to right, accumulating steps. -/
def evalAll (fuel : Nat) (p : Program) (env : Env) :
    List Canon → Sum (List Canon × Nat) (String × Nat)
  | [] => .inl ([], 0)
  | expression :: rest =>
      match eval fuel p env expression with
      | .err kind steps => .inr (kind, steps)
      | .ok value steps =>
          match evalAll fuel p env rest with
          | .inr (kind, more) => .inr (kind, steps + more)
          | .inl (values, more) => .inl (value :: values, steps + more)
termination_by arguments => (fuel, 1, arguments.length)

/-- Tries each case in declared order. Rule order is syntactic. -/
def evalCases (fuel : Nat) (p : Program) (env : Env) (value : Canon) :
    List Canon → Verdict
  | [] => .err "no-match" 0
  | .node "case" [pattern, body] :: rest =>
      match matchPattern pattern value env with
      | some bound => eval fuel p bound body
      | none => evalCases fuel p env value rest
  | _ :: _ => .err "bad-expression" 0
termination_by cases => (fuel, 1, cases.length)

end

set_option maxHeartbeats 2000000 in
/-- A zero budget always yields the canonical exhaustion verdict. -/
theorem eval_zero (p : Program) (env : Env) (e : Canon) :
    eval 0 p env e = .err "resource-exhausted" 0 := by
  rw [eval]

/--
  Determinism.

  `derive(P, env, G)` is a function of its inputs, so two derivations of the
  same goal agree on the value, on the failure kind and on the step count.
-/
theorem eval_deterministic (fuel : Nat) (p : Program) (env : Env) (e : Canon)
    (v₁ v₂ : Verdict) (h₁ : eval fuel p env e = v₁) (h₂ : eval fuel p env e = v₂) :
    v₁ = v₂ := by
  rw [← h₁, ← h₂]

/-- Resource accounting is deterministic too, which is what makes replay exact. -/
theorem steps_deterministic (fuel : Nat) (p : Program) (env : Env) (e : Canon) :
    ∀ v₁ v₂ : Verdict, eval fuel p env e = v₁ → eval fuel p env e = v₂ → v₁ = v₂ :=
  fun v₁ v₂ h₁ h₂ => eval_deterministic fuel p env e v₁ v₂ h₁ h₂

/-- Pattern matching is deterministic. -/
theorem matchPattern_deterministic (pattern value : Canon) (env : Env) :
    ∀ e₁ e₂ : Option Env,
      matchPattern pattern value env = e₁ → matchPattern pattern value env = e₂ → e₁ = e₂ :=
  fun _ _ h₁ h₂ => by rw [← h₁, ← h₂]

/-- Argument evaluation is deterministic. -/
theorem evalAll_deterministic (fuel : Nat) (p : Program) (env : Env) (arguments : List Canon) :
    ∀ r₁ r₂, evalAll fuel p env arguments = r₁ → evalAll fuel p env arguments = r₂ → r₁ = r₂ :=
  fun _ _ h₁ h₂ => by rw [← h₁, ← h₂]

end Stratum
