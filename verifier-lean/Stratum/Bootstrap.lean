/-
  The fixed bootstrap derivation relation, modelled abstractly.

  The property that matters for Stratum is that derivation is a *function* of
  its inputs: for fixed program, closure, constitution, budget and goal there is
  at most one verdict. This file states the relation inductively and proves it
  functional, which is the determinism invariant of `docs/invariants.md`.
-/

namespace Stratum

/-- A core expression of the fixed calculus. -/
inductive Expr where
  | lit : Nat → Expr
  | add : Expr → Expr → Expr
  | ite : Expr → Expr → Expr → Expr
  deriving Repr

/-- Big step derivation. Rule order is syntactic and exhaustive. -/
inductive Eval : Expr → Nat → Prop where
  | lit  : Eval (.lit n) n
  | add  : Eval a x → Eval b y → Eval (.add a b) (x + y)
  | iteZ : Eval c 0 → Eval e v → Eval (.ite c t e) v
  | iteS : Eval c (n + 1) → Eval t v → Eval (.ite c t e) v

/-- Determinism: `derive(P, Σ, K, B, G) = V` is a function. -/
theorem eval_deterministic {e : Expr} : ∀ {v₁ v₂ : Nat}, Eval e v₁ → Eval e v₂ → v₁ = v₂ := by
  induction e with
  | lit n =>
      intro v₁ v₂ h₁ h₂
      cases h₁; cases h₂; rfl
  | add a b iha ihb =>
      intro v₁ v₂ h₁ h₂
      cases h₁ with
      | add ha₁ hb₁ =>
        cases h₂ with
        | add ha₂ hb₂ =>
          rw [iha ha₁ ha₂, ihb hb₁ hb₂]
  | ite c t e ihc iht ihe =>
      intro v₁ v₂ h₁ h₂
      cases h₁ with
      | iteZ hc₁ he₁ =>
        cases h₂ with
        | iteZ hc₂ he₂ => exact ihe he₁ he₂
        | iteS hc₂ ht₂ => exact absurd (ihc hc₁ hc₂) (by simp)
      | iteS hc₁ ht₁ =>
        cases h₂ with
        | iteZ hc₂ he₂ => exact absurd (ihc hc₁ hc₂) (by simp)
        | iteS hc₂ ht₂ => exact iht ht₁ ht₂

/-- A budget bounded evaluator. Exhaustion is a value, never a failure. -/
inductive Verdict where
  | ok        : Nat → Verdict
  | exhausted : Verdict
  deriving Repr, DecidableEq

def run : Nat → Expr → Verdict
  | 0, _ => .exhausted
  | _ + 1, .lit n => .ok n
  | fuel + 1, .add a b =>
      match run fuel a, run fuel b with
      | .ok x, .ok y => .ok (x + y)
      | _, _ => .exhausted
  | fuel + 1, .ite c t e =>
      match run fuel c with
      | .ok 0 => run fuel e
      | .ok _ => run fuel t
      | .exhausted => .exhausted

/-- The bounded evaluator is a function, so replay is reproducible. -/
theorem run_deterministic (fuel : Nat) (e : Expr) (v₁ v₂ : Verdict)
    (h₁ : run fuel e = v₁) (h₂ : run fuel e = v₂) : v₁ = v₂ := by
  rw [← h₁, ← h₂]

/-- Zero budget always yields the canonical exhaustion verdict. -/
theorem run_zero (e : Expr) : run 0 e = .exhausted := by
  cases e <;> rfl

end Stratum
