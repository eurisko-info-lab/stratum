# Sponsoring

Stratum is written by AI agents. Humans set the direction, review what comes
back and decide what ships; the code itself is machine-written. That is a
deliberate constraint, not an accident of tooling, and this page explains what
it costs and what sponsorship pays for.

## Why the constraint

A system that claims never to trust a step it cannot re-derive is a good place
to test whether machine-written code can be held to a higher standard of
evidence than hand-written code usually is — because here the evidence is
mechanical. Every layer is rebuilt from source, agreed on by two independent
hosts, and reconstructible clean-room from its digest alone. An agent cannot
talk its way past any of that, and neither can a person.

So the constraint and the strictness are the same decision seen from two sides.
The gates are not there because the code is machine-written; they are the
reason it is safe for it to be.

## What it costs

The expensive part is not writing the code. It is the loop around it.

A change to a single foundation is not finished when it compiles. It has to
re-elaborate every language source, rebuild the layer, re-derive the canonical
change by which its predecessor constructs it, replay every transcript, agree
digest-for-digest with a second host written in another language, and rebuild
clean-room from executable and closure alone. A wrong turn is discovered at the
end of that, not the beginning, and the agent then has to work out which of its
assumptions the machine has just refused.

That loop, repeated until the gates are green, is what sponsorship buys. In
practice it is metered model inference and the machine time to run the gates —
both per attempt, including the attempts that fail, which are most of them and
are where the design actually gets found.

## What sponsorship changes

More agent hours is the whole of it: more layers on the staircase, more worlds
standing on it, and more of the documentation that makes either legible to
someone who did not write them.

It buys no influence over the design, and no priority on the direction the
project takes. If that is what you are looking for, an issue arguing your case
is worth more than money, and costs you less.

## Contributing without sponsoring

Issues, questions and arguments are welcome and are read by a human. Bug
reports are especially welcome: the most useful thing anyone can do to a system
that claims to be re-derivable is to check, and say so loudly when it is not.

Code contributions sit oddly against the constraint above. If you have a change
you want made, the most direct route is to describe it precisely enough that an
agent can implement it and the gates can judge it — which, if the change is a
good one, is a description worth having anyway.

[Contributing](https://github.com/eurisko-info-lab/stratum/blob/main/CONTRIBUTING.md)
says what a change has to survive, and which boundaries are not negotiable.

## Where

Sponsorship is through [GitHub
Sponsors](https://github.com/sponsors/eurisko-info-lab). If the link is not
live yet, the account is still being set up, and the honest answer is that the
project is very young and you should look at the code first.
