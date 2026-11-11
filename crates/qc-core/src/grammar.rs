//! Syntactic validation of OpenQASM 3 source. Port of `grammar.cljs`.
//!
//! A hand-written tokeniser and recursive-descent parser for the subset of the
//! grammar the editor produces and consumes. The check is purely syntactic: it
//! performs no semantic analysis (unknown gates, out-of-range indices and so on
//! are handled by [`crate::qasm`]).

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum TokenKind {
    Keyword,
    Ident,
    Number,
    String,
    Symbol,
    Hardware,
    Eof,
}

#[derive(Clone, Debug)]
pub struct Token {
    pub kind: TokenKind,
    pub value: String,
    pub line: usize,
    pub col: usize,
}

/// A validation problem with a 1-based line and column.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GrammarError {
    pub line: usize,
    pub col: usize,
    pub message: String,
}

/// The result of validating a program.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Validation {
    pub ok: bool,
    pub errors: Vec<GrammarError>,
}

fn is_keyword(value: &str) -> bool {
    matches!(
        value,
        "OPENQASM"
            | "include"
            | "defcalgrammar"
            | "def"
            | "cal"
            | "defcal"
            | "gate"
            | "extern"
            | "box"
            | "let"
            | "break"
            | "continue"
            | "if"
            | "else"
            | "end"
            | "return"
            | "for"
            | "while"
            | "in"
            | "switch"
            | "case"
            | "default"
            | "nop"
            | "pragma"
            | "input"
            | "output"
            | "const"
            | "readonly"
            | "mutable"
            | "qreg"
            | "qubit"
            | "creg"
            | "bool"
            | "bit"
            | "int"
            | "uint"
            | "float"
            | "angle"
            | "complex"
            | "array"
            | "void"
            | "duration"
            | "stretch"
            | "gphase"
            | "inv"
            | "pow"
            | "ctrl"
            | "negctrl"
            | "durationof"
            | "delay"
            | "reset"
            | "measure"
            | "barrier"
            | "true"
            | "false"
    )
}

fn is_type_keyword(value: &str) -> bool {
    matches!(
        value,
        "bit"
            | "int"
            | "uint"
            | "float"
            | "angle"
            | "bool"
            | "duration"
            | "stretch"
            | "complex"
            | "array"
            | "qubit"
    )
}

fn is_scalar_decl_keyword(value: &str) -> bool {
    matches!(
        value,
        "bit" | "int" | "uint" | "float" | "angle" | "bool" | "duration" | "stretch"
    )
}

fn is_literal_keyword(value: &str) -> bool {
    matches!(value, "true" | "false")
}

fn is_gate_modifier(value: &str) -> bool {
    matches!(value, "inv" | "pow" | "ctrl" | "negctrl")
}

fn is_unary_operator(value: &str) -> bool {
    matches!(value, "-" | "+" | "~" | "!")
}

fn is_assignment_op(value: &str) -> bool {
    matches!(
        value,
        "=" | "+=" | "-=" | "*=" | "/=" | "&=" | "|=" | "^=" | "<<=" | ">>=" | "%=" | "**="
    )
}

fn binary_precedence(op: &str) -> Option<i32> {
    Some(match op {
        "||" => 1,
        "&&" => 2,
        "|" => 3,
        "^" => 4,
        "&" => 5,
        "==" | "!=" => 6,
        "<" | ">" | "<=" | ">=" => 7,
        "<<" | ">>" => 8,
        "+" | "-" => 9,
        "*" | "/" | "%" => 10,
        "**" => 11,
        _ => return None,
    })
}

const MULTI_SYMBOLS: [&str; 22] = [
    "**=", "<<=", ">>=", "->", "++", "**", "||", "&&", "==", "!=", ">=", "<=", ">>", "<<", "+=",
    "-=", "*=", "/=", "&=", "|=", "^=", "%=",
];

const SINGLE_SYMBOLS: &str = "[]{}();:,.=+-*/%|&^@~!<>#";

fn digit(c: char) -> bool {
    c.is_ascii_digit()
}

fn letter(c: char) -> bool {
    c.is_ascii_alphabetic()
}

fn ident_start(c: char) -> bool {
    letter(c) || c == '_' || (c as u32) > 127
}

fn ident_char(c: char) -> bool {
    ident_start(c) || digit(c)
}

fn number_char(c: char) -> bool {
    digit(c) || letter(c) || c == '.' || c == '_'
}

fn end_of_line(chars: &[char], i: usize) -> usize {
    chars[i..]
        .iter()
        .position(|c| *c == '\n')
        .map(|p| i + p)
        .unwrap_or(chars.len())
}

/// Split `text` into tokens and lexical errors.
pub fn tokenize(text: &str) -> (Vec<Token>, Vec<GrammarError>) {
    let chars: Vec<char> = text.chars().collect();
    let n = chars.len();
    let mut tokens: Vec<Token> = Vec::new();
    let mut errors: Vec<GrammarError> = Vec::new();
    let mut i = 0usize;
    let mut line = 1usize;
    let mut line_start = 0usize;

    let tok = |kind: TokenKind, value: String, line: usize, col: usize| Token {
        kind,
        value,
        line,
        col,
    };

    while i < n {
        let c = chars[i];
        let col = i - line_start + 1;

        if c == '\n' {
            i += 1;
            line += 1;
            line_start = i;
            continue;
        }
        if c == '\r' || c == ' ' || c == '\t' {
            i += 1;
            continue;
        }

        // Line comment.
        if c == '/' && i + 1 < n && chars[i + 1] == '/' {
            i = end_of_line(&chars, i);
            continue;
        }

        // Block comment.
        if c == '/' && i + 1 < n && chars[i + 1] == '*' {
            let end = find_seq(&chars, "*/", i + 2);
            match end {
                None => {
                    errors.push(GrammarError {
                        line,
                        col,
                        message: "unterminated block comment".to_string(),
                    });
                    i = n;
                }
                Some(end) => {
                    let segment: String = chars[i..end + 2].iter().collect();
                    let nl = segment.chars().filter(|ch| *ch == '\n').count();
                    if nl > 0 {
                        let last_nl = segment.rfind('\n').unwrap();
                        line += nl;
                        line_start = i + last_nl + 1;
                    }
                    i = end + 2;
                }
            }
            continue;
        }

        // String literal.
        if c == '"' || c == '\'' {
            let quote = c;
            let mut j = i + 1;
            let mut end: Option<usize> = None;
            while j < n {
                if chars[j] == '\n' {
                    break;
                }
                if chars[j] == quote {
                    end = Some(j);
                    break;
                }
                j += 1;
            }
            match end {
                None => {
                    errors.push(GrammarError {
                        line,
                        col,
                        message: "unterminated string literal".to_string(),
                    });
                    i = n;
                }
                Some(end) => {
                    let value: String = chars[i + 1..end].iter().collect();
                    tokens.push(tok(TokenKind::String, value, line, col));
                    i = end + 1;
                }
            }
            continue;
        }

        // Number.
        if digit(c) || (c == '.' && i + 1 < n && digit(chars[i + 1])) {
            let mut j = i;
            while j < n {
                let ch = chars[j];
                if number_char(ch) {
                    j += 1;
                    continue;
                }
                if (ch == '+' || ch == '-') && j > i && (chars[j - 1] == 'e' || chars[j - 1] == 'E')
                {
                    j += 1;
                    continue;
                }
                break;
            }
            let value: String = chars[i..j].iter().collect();
            tokens.push(tok(TokenKind::Number, value, line, col));
            i = j;
            continue;
        }

        // Identifier or keyword.
        if ident_start(c) {
            let mut j = i + 1;
            while j < n && ident_char(chars[j]) {
                j += 1;
            }
            let value: String = chars[i..j].iter().collect();
            let kind = if is_keyword(&value) {
                TokenKind::Keyword
            } else {
                TokenKind::Ident
            };
            tokens.push(tok(kind, value, line, col));
            i = j;
            continue;
        }

        // Hardware qubit.
        if c == '$' {
            let mut j = i + 1;
            while j < n && digit(chars[j]) {
                j += 1;
            }
            if j > i + 1 {
                let value: String = chars[i..j].iter().collect();
                tokens.push(tok(TokenKind::Hardware, value, line, col));
                i = j;
            } else {
                errors.push(GrammarError {
                    line,
                    col,
                    message: "expected digits after '$'".to_string(),
                });
                i += 1;
            }
            continue;
        }

        // Symbols.
        let remaining: String = chars[i..n.min(i + 4)].iter().collect();
        let multi = MULTI_SYMBOLS
            .iter()
            .find(|sym| remaining.starts_with(**sym));
        if let Some(sym) = multi {
            tokens.push(tok(TokenKind::Symbol, (*sym).to_string(), line, col));
            i += sym.chars().count();
            continue;
        }
        if SINGLE_SYMBOLS.contains(c) {
            tokens.push(tok(TokenKind::Symbol, c.to_string(), line, col));
            i += 1;
            continue;
        }
        errors.push(GrammarError {
            line,
            col,
            message: format!("unexpected character '{}'", c),
        });
        i += 1;
    }

    tokens.push(Token {
        kind: TokenKind::Eof,
        value: String::new(),
        line,
        col: 1,
    });
    (tokens, errors)
}

fn find_seq(chars: &[char], needle: &str, from: usize) -> Option<usize> {
    let pat: Vec<char> = needle.chars().collect();
    if pat.is_empty() || chars.len() < pat.len() {
        return None;
    }
    let mut i = from;
    while i + pat.len() <= chars.len() {
        if chars[i..i + pat.len()] == pat[..] {
            return Some(i);
        }
        i += 1;
    }
    None
}

struct Parser {
    tokens: Vec<Token>,
    pos: usize,
    errors: Vec<GrammarError>,
}

impl Parser {
    fn new(tokens: Vec<Token>, errors: Vec<GrammarError>) -> Self {
        Parser {
            tokens,
            pos: 0,
            errors,
        }
    }

    fn current(&self) -> &Token {
        self.tokens
            .get(self.pos)
            .unwrap_or_else(|| self.tokens.last().unwrap())
    }

    fn current_value(&self) -> &str {
        &self.current().value
    }

    fn current_type(&self) -> TokenKind {
        self.current().kind
    }

    fn at(&self, value: &str) -> bool {
        self.current().value == value
    }

    fn at_type(&self, kind: TokenKind) -> bool {
        self.current().kind == kind
    }

    fn advance(&mut self) {
        if self.pos < self.tokens.len() {
            self.pos += 1;
        }
    }

    fn add_error(&mut self, message: &str) {
        let t = self.current();
        self.errors.push(GrammarError {
            line: t.line,
            col: t.col,
            message: message.to_string(),
        });
    }

    fn expect_symbol(&mut self, sym: &str, message: &str) {
        if self.at(sym) {
            self.advance();
        } else {
            self.add_error(message);
        }
    }

    // --- expressions ---

    fn parse_primary(&mut self) {
        let kind = self.current_type();
        let value = self.current().value.clone();
        match kind {
            TokenKind::Number | TokenKind::String | TokenKind::Hardware => self.advance(),
            TokenKind::Keyword if is_literal_keyword(&value) => self.advance(),
            TokenKind::Keyword if is_type_keyword(&value) => {
                self.advance();
                if self.at("(") {
                    self.parse_call_args();
                } else {
                    self.add_error(&format!("expected '(' after type cast '{}'", value));
                }
            }
            TokenKind::Ident => {
                self.advance();
                if self.at("(") {
                    self.parse_call_args();
                }
            }
            TokenKind::Symbol if value == "(" => {
                self.advance();
                self.parse_expression();
                self.expect_symbol(")", "expected ')'");
            }
            _ => self.add_error("expected an expression"),
        }
    }

    fn parse_postfix(&mut self) {
        while self.at("[") {
            self.parse_index();
        }
    }

    fn parse_unary(&mut self) {
        if self.current_type() == TokenKind::Symbol && is_unary_operator(self.current_value()) {
            self.advance();
            self.parse_unary();
        } else {
            self.parse_primary();
            self.parse_postfix();
        }
    }

    fn parse_binary(&mut self, min_prec: i32) {
        loop {
            let op = self.current_value().to_string();
            let prec = match binary_precedence(&op) {
                Some(p) if p >= min_prec => p,
                _ => break,
            };
            self.advance();
            let next_min = if op == "**" { prec } else { prec + 1 };
            self.parse_unary();
            self.parse_binary(next_min);
        }
    }

    fn parse_expression(&mut self) {
        self.parse_unary();
        self.parse_binary(1);
    }

    fn parse_index_item(&mut self) {
        if !self.at(":") {
            self.parse_expression();
        }
        if self.at(":") {
            self.advance();
            if !(self.at(":") || self.at("]") || self.at(",")) {
                self.parse_expression();
            }
            if self.at(":") {
                self.advance();
                if !(self.at("]") || self.at(",")) {
                    self.parse_expression();
                }
            }
        }
    }

    fn parse_index(&mut self) {
        self.advance(); // '['
        if !self.at("]") {
            self.parse_index_item();
            while self.at(",") {
                self.advance();
                if self.at("]") {
                    break;
                }
                self.parse_index_item();
            }
        }
        self.expect_symbol("]", "expected ']'");
    }

    fn parse_call_args(&mut self) {
        self.advance(); // '('
        if !self.at(")") {
            self.parse_expression();
            while self.at(",") {
                self.advance();
                if self.at(")") {
                    break;
                }
                self.parse_expression();
            }
        }
        self.expect_symbol(")", "expected ')'");
    }

    // --- operands ---

    fn parse_indexed_identifier(&mut self) {
        self.advance(); // ident
        while self.at("[") {
            self.parse_index();
        }
    }

    fn gate_operand(&self) -> bool {
        self.at_type(TokenKind::Hardware) || self.at_type(TokenKind::Ident)
    }

    fn parse_gate_operand(&mut self) {
        if self.at_type(TokenKind::Hardware) {
            self.advance();
        } else if self.at_type(TokenKind::Ident) {
            self.parse_indexed_identifier();
        } else {
            self.add_error("expected a gate operand");
        }
    }

    fn parse_gate_operand_list(&mut self) {
        if self.gate_operand() {
            self.parse_gate_operand();
            while self.at(",") {
                self.advance();
                if self.gate_operand() {
                    self.parse_gate_operand();
                } else {
                    break;
                }
            }
        }
    }

    // --- statements ---

    fn parse_version(&mut self) {
        self.advance(); // OPENQASM
        if self.at_type(TokenKind::Number) {
            self.advance();
        } else {
            self.add_error("expected a version number after OPENQASM");
        }
        self.expect_symbol(";", "expected ';' after the version");
    }

    fn parse_include(&mut self) {
        self.advance();
        if self.at_type(TokenKind::String) {
            self.advance();
        } else {
            self.add_error("expected a string after include");
        }
        self.expect_symbol(";", "expected ';' after the include path");
    }

    fn parse_declaration_expression(&mut self) {
        if self.at("measure") {
            self.parse_measure_expression();
        } else {
            self.parse_expression();
        }
    }

    fn parse_register_decl(&mut self, message: &str) {
        self.advance();
        if self.at("[") {
            self.parse_index();
        }
        if self.at_type(TokenKind::Ident) {
            self.advance();
        } else {
            self.add_error("expected a register name");
        }
        if self.at("=") {
            self.advance();
            self.parse_declaration_expression();
        }
        self.expect_symbol(";", message);
    }

    fn parse_paren_params(&mut self) {
        self.advance(); // '('
        if self.at_type(TokenKind::Ident) {
            self.advance();
            while self.at(",") {
                self.advance();
                if self.at_type(TokenKind::Ident) {
                    self.advance();
                } else {
                    break;
                }
            }
        }
        self.expect_symbol(")", "expected ')' after the parameter list");
    }

    fn parse_bare_identifier_list(&mut self, message: &str) {
        if self.at_type(TokenKind::Ident) {
            self.advance();
            while self.at(",") {
                self.advance();
                if self.at_type(TokenKind::Ident) {
                    self.advance();
                } else {
                    break;
                }
            }
        } else {
            self.add_error(message);
        }
    }

    fn parse_gate_def(&mut self) {
        self.advance(); // gate
        if self.at_type(TokenKind::Ident) {
            self.advance();
        } else {
            self.add_error("expected a gate name");
        }
        if self.at("(") {
            self.parse_paren_params();
        }
        self.parse_bare_identifier_list("expected the gate qubit parameters");
        if self.at("{") {
            self.parse_scope();
        } else {
            self.add_error("expected '{' to begin the gate body");
        }
    }

    fn parse_measure(&mut self) {
        self.advance();
        self.parse_gate_operand();
        if self.at("->") {
            self.advance();
            if self.at_type(TokenKind::Ident) {
                self.parse_indexed_identifier();
            } else {
                self.add_error("expected a classical bit target after '->'");
            }
        }
        self.expect_symbol(";", "expected ';' after measure");
    }

    fn parse_barrier(&mut self) {
        self.advance();
        self.parse_gate_operand_list();
        self.expect_symbol(";", "expected ';' after barrier");
    }

    fn parse_reset(&mut self) {
        self.advance();
        self.parse_gate_operand();
        self.expect_symbol(";", "expected ';' after reset");
    }

    fn parse_gate_modifiers(&mut self) {
        loop {
            let value = self.current_value().to_string();
            if self.current_type() == TokenKind::Keyword && is_gate_modifier(&value) {
                self.advance();
                if value != "inv" && self.at("(") {
                    self.parse_call_args();
                }
                if self.at("@") {
                    self.advance();
                } else {
                    self.add_error("expected '@' after a gate modifier");
                }
            } else {
                break;
            }
        }
    }

    fn parse_measure_expression(&mut self) {
        self.advance();
        self.parse_gate_operand();
    }

    fn parse_invocation(&mut self) {
        self.parse_gate_modifiers();
        if self.at_type(TokenKind::Ident) || self.at("gphase") {
            let name = self.current_value().to_string();
            self.advance();
            let call = self.at("(");
            if call {
                self.parse_call_args();
            }
            if self.at("[") {
                self.parse_index();
            }
            if is_assignment_op(self.current_value()) {
                self.advance();
                if self.at("measure") {
                    self.parse_measure_expression();
                } else {
                    self.parse_expression();
                }
                self.expect_symbol(";", "expected ';' after the assignment");
            } else {
                let start = self.pos;
                self.parse_gate_operand_list();
                let has_operands = self.pos > start;
                if !call && name != "gphase" && !has_operands {
                    self.add_error("expected at least one gate operand");
                } else {
                    self.expect_symbol(";", "expected ';' after the gate call");
                }
            }
        } else {
            self.add_error("expected a gate or function name");
        }
    }

    fn parse_expression_statement(&mut self) {
        self.parse_expression();
        self.expect_symbol(";", "expected ';' after the expression");
    }

    fn parse_statement(&mut self) {
        let value = self.current_value().to_string();
        let kind = self.current_type();
        match kind {
            TokenKind::Keyword => match value.as_str() {
                "OPENQASM" => self.parse_version(),
                "include" => self.parse_include(),
                "gate" => self.parse_gate_def(),
                "measure" => self.parse_measure(),
                "barrier" => self.parse_barrier(),
                "reset" => self.parse_reset(),
                "qubit" => self.parse_register_decl("expected ';' after the qubit declaration"),
                _ if is_scalar_decl_keyword(&value) => {
                    self.parse_register_decl("expected ';' after the declaration")
                }
                "gphase" => self.parse_invocation(),
                _ if is_gate_modifier(&value) => self.parse_invocation(),
                _ if is_literal_keyword(&value) => self.parse_expression_statement(),
                _ => self.add_error(&format!(
                    "statement '{}' is not supported by this validator",
                    value
                )),
            },
            TokenKind::Ident => self.parse_invocation(),
            TokenKind::Number | TokenKind::String | TokenKind::Hardware => {
                self.parse_expression_statement()
            }
            TokenKind::Symbol => {
                if value == "{" {
                    self.parse_scope();
                } else if value == "(" {
                    self.parse_expression_statement();
                } else {
                    self.add_error(&format!("unexpected token '{}'", value));
                }
            }
            _ => self.add_error("unexpected end of input"),
        }
    }

    fn parse_statement_or_scope(&mut self) {
        if self.at("{") {
            self.parse_scope();
        } else {
            self.parse_statement();
        }
    }

    fn recover_statement(&mut self) {
        loop {
            let t = self.current().clone();
            if t.kind == TokenKind::Eof {
                return;
            }
            if t.value == "}" {
                return;
            }
            if t.value == ";" {
                self.advance();
                return;
            }
            self.advance();
        }
    }

    fn parse_statement_safe(&mut self) {
        let before = self.pos;
        self.parse_statement_or_scope();
        if self.pos == before {
            self.recover_statement();
            if self.pos == before {
                self.advance();
            }
        }
    }

    fn parse_scope(&mut self) {
        self.advance(); // '{'
        loop {
            let t = self.current().clone();
            if t.value == "}" {
                self.advance();
                return;
            }
            if t.kind == TokenKind::Eof {
                self.add_error("unexpected end of input, expected '}'");
                return;
            }
            self.parse_statement_safe();
        }
    }

    fn parse_program(&mut self) {
        if self.at("OPENQASM") {
            self.parse_version();
        }
        while self.current_type() != TokenKind::Eof {
            self.parse_statement_safe();
        }
    }
}

/// Validate `text` against the supported OpenQASM 3 subset.
pub fn validate(text: &str) -> Validation {
    let (tokens, errors) = tokenize(text);
    let mut parser = Parser::new(tokens, errors);
    parser.parse_program();
    if parser.errors.is_empty() {
        Validation {
            ok: true,
            errors: Vec::new(),
        }
    } else {
        Validation {
            ok: false,
            errors: parser.errors,
        }
    }
}

/// Convenience predicate.
pub fn valid(text: &str) -> bool {
    validate(text).ok
}

/// The first validation error, if any.
pub fn first_error(text: &str) -> Option<GrammarError> {
    validate(text).errors.into_iter().next()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_is_valid() {
        assert!(valid(""));
    }

    #[test]
    fn basic_program_valid() {
        let text = "OPENQASM 3;\ninclude \"stdgates.inc\";\nqubit[2] q;\nbit[2] c;\nh q[0];\ncx q[0], q[1];\nmeasure q[0] -> c[0];\n";
        assert!(valid(text), "{:?}", validate(text).errors);
    }

    #[test]
    fn gate_def_valid() {
        let text = "gate cphase(theta) a, b\n{\n  U(0, 0, theta / 2) a;\n  CX a, b;\n}\n";
        assert!(valid(text), "{:?}", validate(text).errors);
    }

    #[test]
    fn modifiers_valid() {
        assert!(
            valid("ctrl @ x q[0], q[1];"),
            "{:?}",
            validate("ctrl @ x q[0], q[1];").errors
        );
        assert!(valid("pow(2) @ x q[0];"));
        assert!(valid("inv @ x q[0];"));
    }

    #[test]
    fn missing_semicolon_invalid() {
        let v = validate("qubit[2] q\n");
        assert!(!v.ok);
        assert!(!v.errors.is_empty());
    }

    #[test]
    fn unsupported_statement_reported() {
        let v = validate("for i in [0:1] { x q[0]; }");
        assert!(!v.ok);
        assert!(v.errors.iter().any(|e| e.message.contains("not supported")));
    }

    #[test]
    fn unterminated_string_reported() {
        let v = validate("include \"stdgates.inc;\n");
        assert!(!v.ok);
        assert!(v
            .errors
            .iter()
            .any(|e| e.message.contains("unterminated string")));
    }

    #[test]
    fn unexpected_character_reported() {
        let v = validate("qubit[2] q;\n~;");
        // '~' is a valid symbol (unary) but this is not a valid statement.
        assert!(!v.ok);
    }

    #[test]
    fn error_has_line_and_column() {
        let v = validate("qubit[2] q");
        let err = v.errors.first().unwrap();
        assert_eq!(1, err.line);
        assert!(err.col > 0);
    }

    #[test]
    fn reset_and_barrier_valid() {
        assert!(valid("reset q[0];"));
        assert!(valid("barrier q[0], q[1];"));
        assert!(valid("barrier;"));
    }

    fn kinds(text: &str) -> Vec<TokenKind> {
        tokenize(text).0.iter().map(|t| t.kind).collect()
    }

    fn lines(text: &str) -> Vec<usize> {
        tokenize(text).0.iter().map(|t| t.line).collect()
    }

    fn messages(text: &str) -> Vec<String> {
        tokenize(text).1.into_iter().map(|e| e.message).collect()
    }

    #[test]
    fn tokenize_basic_test() {
        let (tokens, errors) = tokenize("qubit[2] q; // c\nbit[2] c;");
        assert!(errors.is_empty());
        use TokenKind::*;
        assert_eq!(
            vec![
                Keyword, Symbol, Number, Symbol, Ident, Symbol, Keyword, Symbol, Number, Symbol,
                Ident, Symbol, Eof
            ],
            kinds("qubit[2] q; // c\nbit[2] c;")
        );
        assert_eq!(
            vec![1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2],
            tokens.iter().map(|t| t.line).collect::<Vec<_>>()
        );
    }

    #[test]
    fn tokenize_comments_and_strings_test() {
        let (_, errors) = tokenize("/* a\nb */ x");
        assert!(errors.is_empty());
        use TokenKind::*;
        assert_eq!(vec![Ident, Eof], kinds("/* a\nb */ x"));
        assert_eq!(vec![2, 2], lines("/* a\nb */ x"));

        assert_eq!(vec![1, 2, 2], lines("x // note\ny"));

        let (tokens, _) = tokenize("include \"stdgates.inc\";");
        assert_eq!(TokenKind::String, tokens[1].kind);
        assert_eq!("stdgates.inc", tokens[1].value);
    }

    #[test]
    fn tokenize_errors_test() {
        assert_eq!(vec!["unterminated block comment"], messages("/* nope"));
        assert_eq!(
            vec!["unterminated string literal"],
            messages("include \"oops;")
        );
        assert_eq!(vec!["unexpected character '?'"], messages("?"));
        assert_eq!(1, validate("?").errors.len());
    }

    #[test]
    fn empty_program_test() {
        assert!(valid(""));
        assert!(valid("   \n\t  "));
    }

    #[test]
    fn version_and_include_test() {
        assert!(valid("OPENQASM 3;"));
        assert!(valid("OPENQASM 3.1;"));
        assert!(valid("OPENQASM 3;\ninclude \"stdgates.inc\";"));
        assert!(!valid("OPENQASM;"));
        assert!(!valid("OPENQASM 3"));
        assert!(!valid("include \"stdgates.inc\""));
    }

    #[test]
    fn register_declaration_test() {
        assert!(valid("qubit[3] q;"));
        assert!(valid("bit[3] c;"));
        assert!(valid("qubit q;"));
        assert!(!valid("qubit[3];"));
        assert!(!valid("qubit[3] q"));
        assert!(!valid("qubit[3 q;"));
    }

    #[test]
    fn gate_call_test() {
        assert!(valid("qubit[2] q;\nh q[0];"));
        assert!(valid("qubit[2] q;\ncx q[0], q[1];"));
        assert!(valid("qubit[2] q;\nrx(pi/2) q[0];"));
        assert!(valid("qubit[2] q;\nrz(3*pi/4) q[1];"));
        assert!(valid("qubit[2] q;\nu(0, 0, theta / 2) q[0];"));
        assert!(valid("qubit[2] q;\nctrl @ x q[0], q[1];"));
        assert!(valid("qubit[2] q;\npow(2) @ x q[0];"));
        assert!(valid("qubit[2] q;\ninv @ cx q[0], q[1];"));
        assert!(valid("qubit[1] q;\nh $0;"));
        assert!(!valid("qubit[1] q;\nh;"));
        assert!(!valid("qubit[1] q;\ninv x q[0];"));
    }

    #[test]
    fn measure_barrier_reset_test() {
        assert!(valid("qubit[1] q;\nmeasure q[0];"));
        assert!(valid("qubit[1] q;\nbit[1] c;\nmeasure q[0] -> c[0];"));
        assert!(valid("qubit[2] q;\nbarrier;"));
        assert!(valid("qubit[2] q;\nbarrier q[0], q[1];"));
        assert!(valid("qubit[1] q;\nreset q[0];"));
        assert!(!valid("qubit[1] q;\nmeasure;"));
        assert!(!valid("qubit[1] q;\nmeasure q[0] -> ;"));
        assert!(!valid("qubit[1] q;\nreset;"));
    }

    #[test]
    fn gate_definition_test() {
        assert!(valid(
            "qubit[2] q;\ngate mygate a, b { cx a, b; }\nmygate q[0], q[1];"
        ));
        assert!(valid(
            "qubit[2] q;\ngate cphase(theta) a, b\n{\n  U(0, 0, theta / 2) a;\n  CX a, b;\n  U(0, 0, -theta / 2) b;\n}\ncphase(pi / 2) q[0], q[1];"
        ));
        assert!(valid(
            "qubit[2] q;\ngate cphase(\u{03b8}) a, b\n{\n  U(0, 0, \u{03b8} / 2) a;\n  CX a, b;\n}\ncphase(\u{03c0} / 2) q[0], q[1];"
        ));
        assert!(!valid("qubit[1] q;\ngate foo a { x a;"));
        assert!(!valid("qubit[1] q;\ngate foo { x a; }"));
    }

    #[test]
    fn expression_precedence_test() {
        assert!(valid("qubit[1] q;\nrx(1 + 2 * 3) q[0];"));
        assert!(valid("qubit[1] q;\nrx(2 ** 3 ** 4) q[0];"));
        assert!(valid("qubit[1] q;\nrx(-pi / 2) q[0];"));
        assert!(valid("qubit[1] q;\nrx((pi + pi) / 4) q[0];"));
        assert!(valid("qubit[1] q;\nrx(0x1f) q[0];"));
        assert!(valid("qubit[1] q;\nrx(1e-3) q[0];"));
    }

    #[test]
    fn assignment_and_call_statements_test() {
        assert!(valid("OPENQASM 3;\nint[32] i = 4;\ni += 1;"));
        assert!(valid("qubit[1] q;\nx(pi);"));
        assert!(valid("qubit[1] q;\nc = measure q[0];"));
    }

    #[test]
    fn unsupported_statement_test() {
        let res = validate("if (x) { y; }");
        assert!(!res.ok);
        assert!(res.errors[0].message.contains("not supported"));
    }

    #[test]
    fn error_location_test() {
        let res = validate("qubit[2] q;\nh q[0];\nqubit[2 q;");
        assert!(!res.ok);
        assert_eq!(3, res.errors[0].line);
    }

    #[test]
    fn multiple_errors_test() {
        let res = validate("qubit[1] q;\nif (x) { y; }\nfor (i in [1:2]) { z; }");
        assert!(!res.ok);
        assert!(res.errors.len() >= 2);
    }

    #[test]
    fn valid_predicate_test() {
        assert!(valid("qubit[1] q;\nh q[0];"));
        assert!(!valid("qubit[1] q;\nh q[0]"));
        assert!(first_error("qubit[1] q;\nh q[0];").is_none());
        assert!(first_error("qubit[1] q; h q[0]").is_some());
    }

    #[test]
    fn generated_code_is_grammatical_test() {
        use crate::model::{Gate, GateDef, GateKind, State};
        use crate::qasm::generate_qasm;
        use std::collections::BTreeMap;

        let mut defs = BTreeMap::new();
        defs.insert(
            "mygate".to_string(),
            GateDef {
                arity: 2,
                lines: vec!["gate mygate a, b { cx a, b; }".to_string()],
            },
        );
        let state = State {
            qubits: 3,
            cbits: 3,
            defs,
            gates: vec![
                Gate {
                    id: 0,
                    ..Gate::new(GateKind::H, 0, 0)
                },
                Gate {
                    id: 1,
                    angle: Some("pi/2".into()),
                    ..Gate::new(GateKind::Rx, 1, 0)
                },
                Gate {
                    id: 2,
                    target: Some(2),
                    ..Gate::new(GateKind::Cx, 0, 1)
                },
                Gate {
                    id: 3,
                    name: Some("mygate".into()),
                    wires: vec![0, 1],
                    ..Gate::new(GateKind::Custom, 0, 2)
                },
                Gate {
                    id: 4,
                    wires: vec![0, 1, 2],
                    ..Gate::new(GateKind::Barrier, 0, 3)
                },
                Gate {
                    id: 5,
                    ..Gate::new(GateKind::Measure, 0, 4)
                },
            ],
            ..Default::default()
        };
        assert!(
            valid(&generate_qasm(&state)),
            "{:?}",
            validate(&generate_qasm(&state)).errors
        );
        assert!(valid(&generate_qasm(&State {
            qubits: 4,
            ..Default::default()
        })));
    }
}
