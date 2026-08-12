//! A minimal, read-only decoder for Stratum's canonical text form
//! (host-scala/canon/CanonText.scala's `write` output), just capable enough
//! to load a `Program` artifact produced by the Meta-level compiler
//! (features/rustvm/compile.meta's `CompileRustSource`). No encoder, no
//! digesting, no CAS: this crate consumes one compiler's output, it does not
//! participate in the content-addressed closure the way host-rust does.

#[derive(Debug, Clone)]
pub enum Value {
    Node(String, Vec<Value>),
    List(Vec<Value>),
    Str(String),
    Nat(i64),
    Sym(String),
}

impl Value {
    pub fn as_node(&self) -> Result<(&str, &[Value]), String> {
        match self {
            Value::Node(tag, args) => Ok((tag.as_str(), args.as_slice())),
            other => Err(format!("expected a node, found {other:?}")),
        }
    }

    pub fn as_list(&self) -> Result<&[Value], String> {
        match self {
            Value::List(items) => Ok(items.as_slice()),
            other => Err(format!("expected a list, found {other:?}")),
        }
    }

    pub fn as_nat(&self) -> Result<i64, String> {
        match self {
            Value::Nat(n) => Ok(*n),
            other => Err(format!("expected a number, found {other:?}")),
        }
    }

    pub fn as_sym(&self) -> Result<&str, String> {
        match self {
            Value::Sym(s) => Ok(s.as_str()),
            other => Err(format!("expected a symbol, found {other:?}")),
        }
    }
}

pub fn read(input: &str) -> Result<Value, String> {
    let chars: Vec<char> = input.chars().collect();
    let mut pos = 0usize;
    let value = parse_value(&chars, &mut pos)?;
    skip_trivia(&chars, &mut pos);
    if pos != chars.len() {
        return Err("trailing input".to_string());
    }
    Ok(value)
}

fn skip_trivia(chars: &[char], pos: &mut usize) {
    loop {
        let mut moved = false;
        while *pos < chars.len() && chars[*pos].is_whitespace() {
            *pos += 1;
            moved = true;
        }
        if *pos < chars.len() && chars[*pos] == ';' {
            while *pos < chars.len() && chars[*pos] != '\n' {
                *pos += 1;
            }
            moved = true;
        }
        if !moved {
            break;
        }
    }
}

fn is_delimiter(c: char) -> bool {
    matches!(c, '(' | ')' | '[' | ']' | '{' | '}' | '"' | ';')
}

fn parse_value(chars: &[char], pos: &mut usize) -> Result<Value, String> {
    skip_trivia(chars, pos);
    if *pos >= chars.len() {
        return Err("unexpected end of input".to_string());
    }
    match chars[*pos] {
        '(' => parse_node(chars, pos),
        '[' => parse_list(chars, pos),
        '"' => Ok(Value::Str(parse_string(chars, pos)?)),
        '#' => parse_hash(chars, pos),
        ')' | ']' | '}' => Err(format!("unexpected '{}'", chars[*pos])),
        _ => parse_atom(chars, pos),
    }
}

fn parse_node(chars: &[char], pos: &mut usize) -> Result<Value, String> {
    *pos += 1;
    skip_trivia(chars, pos);
    let tag = match parse_value(chars, pos)? {
        Value::Sym(s) => s,
        other => return Err(format!("node tag must be a symbol, found {other:?}")),
    };
    let mut args = Vec::new();
    skip_trivia(chars, pos);
    while *pos < chars.len() && chars[*pos] != ')' {
        args.push(parse_value(chars, pos)?);
        skip_trivia(chars, pos);
    }
    if *pos >= chars.len() {
        return Err("unterminated node".to_string());
    }
    *pos += 1;
    Ok(Value::Node(tag, args))
}

fn parse_list(chars: &[char], pos: &mut usize) -> Result<Value, String> {
    *pos += 1;
    let mut items = Vec::new();
    skip_trivia(chars, pos);
    while *pos < chars.len() && chars[*pos] != ']' {
        items.push(parse_value(chars, pos)?);
        skip_trivia(chars, pos);
    }
    if *pos >= chars.len() {
        return Err("unterminated list".to_string());
    }
    *pos += 1;
    Ok(Value::List(items))
}

fn parse_string(chars: &[char], pos: &mut usize) -> Result<String, String> {
    *pos += 1;
    let mut out = String::new();
    loop {
        if *pos >= chars.len() {
            return Err("unterminated string".to_string());
        }
        let c = chars[*pos];
        *pos += 1;
        if c == '"' {
            break;
        } else if c == '\\' {
            if *pos >= chars.len() {
                return Err("unterminated escape".to_string());
            }
            let e = chars[*pos];
            *pos += 1;
            match e {
                'n' => out.push('\n'),
                't' => out.push('\t'),
                'r' => out.push('\r'),
                '\\' => out.push('\\'),
                '"' => out.push('"'),
                'u' => {
                    let hex: String = chars[*pos..*pos + 4].iter().collect();
                    *pos += 4;
                    let code = u32::from_str_radix(&hex, 16).map_err(|e| e.to_string())?;
                    out.push(char::from_u32(code).ok_or("bad unicode escape")?);
                }
                other => return Err(format!("unknown escape \\{other}")),
            }
        } else {
            out.push(c);
        }
    }
    Ok(out)
}

fn parse_hash(chars: &[char], pos: &mut usize) -> Result<Value, String> {
    *pos += 1;
    let token = read_token(chars, pos);
    match token.as_str() {
        "unit" => Ok(Value::Sym("unit".to_string())),
        "t" => Ok(Value::Sym("t".to_string())),
        "f" => Ok(Value::Sym("f".to_string())),
        other => Err(format!("unsupported # literal: #{other}")),
    }
}

fn read_token(chars: &[char], pos: &mut usize) -> String {
    let mut out = String::new();
    while *pos < chars.len() && !chars[*pos].is_whitespace() && !is_delimiter(chars[*pos]) {
        out.push(chars[*pos]);
        *pos += 1;
    }
    out
}

fn parse_atom(chars: &[char], pos: &mut usize) -> Result<Value, String> {
    let token = read_token(chars, pos);
    if token.is_empty() {
        return Err("empty token".to_string());
    }
    if token.chars().all(|c| c.is_ascii_digit()) {
        return token.parse::<i64>().map(Value::Nat).map_err(|e| e.to_string());
    }
    if (token.starts_with('-') || token.starts_with('+'))
        && token.len() > 1
        && token[1..].chars().all(|c| c.is_ascii_digit())
    {
        let stripped = if token.starts_with('+') { &token[1..] } else { token.as_str() };
        return stripped.parse::<i64>().map(Value::Nat).map_err(|e| e.to_string());
    }
    Ok(Value::Sym(token))
}
