const EXPRESSION_FUNCTIONS = new Set([
  'exists', 'coalesce', 'if', 'concat', 'length',
  'divide', 'round', 'formatDecimal', 'formatDate', 'formatTime',
]);

export interface TemplateExpressionSyntaxResult {
  readonly valid: boolean;
  readonly usedAliases: ReadonlySet<string>;
  readonly astNodes: number;
}

type ParsedNode = { readonly kind: 'decimal'; readonly token: string }
  | { readonly kind: 'other' };

const OTHER_NODE: ParsedNode = Object.freeze({ kind: 'other' });

/**
 * Conservative client parser for renderweave-expression/1.0. It proves only
 * syntax that the server ExpressionParser accepts and never normalizes source.
 */
export function parseTemplateExpressionSource(source: string): TemplateExpressionSyntaxResult {
  if (new TextEncoder().encode(source).byteLength > 65_536) {
    return { valid: false, usedAliases: new Set(), astNodes: 0 };
  }
  const parser = new TemplateExpressionParser(source);
  return parser.parse();
}

class TemplateExpressionParser {
  private position = 0;
  private failed = false;
  private depth = 0;
  private nodes = 0;
  private readonly usedAliases = new Set<string>();
  private lastNode: ParsedNode = OTHER_NODE;

  constructor(private readonly source: string) {}

  parse(): TemplateExpressionSyntaxResult {
    const parsed = this.parseExpression();
    this.skipWhitespace();
    return {
      valid: parsed && !this.failed && this.position === this.source.length,
      usedAliases: this.usedAliases,
      astNodes: this.nodes,
    };
  }

  private parseExpression(): boolean {
    this.depth += 1;
    if (this.depth > 256) {
      this.failed = true;
      this.depth -= 1;
      return false;
    }
    const parsed = this.parseOr();
    this.depth -= 1;
    return parsed;
  }

  private parseOr(): boolean {
    if (!this.parseAnd()) return false;
    let node = this.lastNode;
    while (this.take('||')) {
      if (!this.parseAnd() || !this.reserveNode()) return false;
      node = OTHER_NODE;
    }
    this.lastNode = node;
    return true;
  }

  private parseAnd(): boolean {
    if (!this.parseEquality()) return false;
    let node = this.lastNode;
    while (this.take('&&')) {
      if (!this.parseEquality() || !this.reserveNode()) return false;
      node = OTHER_NODE;
    }
    this.lastNode = node;
    return true;
  }

  private parseEquality(): boolean {
    if (!this.parseRelational()) return false;
    let node = this.lastNode;
    while (true) {
      if (this.take('==') || this.take('!=')) {
        if (!this.parseRelational() || !this.reserveNode()) return false;
        node = OTHER_NODE;
      } else {
        this.lastNode = node;
        return true;
      }
    }
  }

  private parseRelational(): boolean {
    if (!this.parseAdditive()) return false;
    let node = this.lastNode;
    while (true) {
      if (this.take('<=') || this.take('>=') || this.take('<') || this.take('>')) {
        if (!this.parseAdditive() || !this.reserveNode()) return false;
        node = OTHER_NODE;
      } else {
        this.lastNode = node;
        return true;
      }
    }
  }

  private parseAdditive(): boolean {
    if (!this.parseMultiplicative()) return false;
    let node = this.lastNode;
    while (true) {
      if (this.take('+') || this.take('-')) {
        if (!this.parseMultiplicative() || !this.reserveNode()) return false;
        node = OTHER_NODE;
      } else {
        this.lastNode = node;
        return true;
      }
    }
  }

  private parseMultiplicative(): boolean {
    if (!this.parseUnary()) return false;
    let node = this.lastNode;
    while (this.take('*')) {
      if (!this.parseUnary() || !this.reserveNode()) return false;
      node = OTHER_NODE;
    }
    this.lastNode = node;
    return true;
  }

  private parseUnary(): boolean {
    let unaryCount = 0;
    while (this.take('!') || this.take('-')) {
      unaryCount += 1;
      if (unaryCount > 4096) return this.reject();
    }
    if (!this.parsePrimary()) return false;
    for (let index = 0; index < unaryCount; index += 1) {
      if (!this.reserveNode()) return false;
    }
    if (unaryCount > 0) this.lastNode = OTHER_NODE;
    return true;
  }

  private parsePrimary(): boolean {
    this.skipWhitespace();
    const current = this.source[this.position];
    if (current === undefined) return this.reject();
    if (current === '(') {
      this.position += 1;
      return this.parseExpression() && this.take(')');
    }
    if (current === "'") return this.parseTextLiteral();
    if (current >= '0' && current <= '9') return this.parseDecimalLiteral();
    if (isIdentifierStart(current)) return this.parseIdentifierLed();
    return this.reject();
  }

  private parseIdentifierLed(): boolean {
    const name = this.readIdentifier();
    if (name === null) return false;
    this.skipWhitespace();
    if (this.source[this.position] === '(') {
      if (!EXPRESSION_FUNCTIONS.has(name)) return this.reject();
      this.position += 1;
      const arguments_: ParsedNode[] = [];
      this.skipWhitespace();
      if (this.take(')')) {
        if (!this.validExplicitRoundingScales(name, arguments_) || !this.reserveNode()) return false;
        this.lastNode = OTHER_NODE;
        return true;
      }
      while (true) {
        if (!this.parseExpression()) return false;
        arguments_.push(this.lastNode);
        if (this.take(',')) continue;
        if (this.take(')')) {
          if (!this.validExplicitRoundingScales(name, arguments_) || !this.reserveNode()) return false;
          this.lastNode = OTHER_NODE;
          return true;
        }
        return this.reject();
      }
    }
    if (name === 'true' || name === 'false') {
      if (!this.reserveNode()) return false;
      this.lastNode = OTHER_NODE;
      return true;
    }
    if (name !== 'input' || !this.take('.')) return this.reject();
    const alias = this.readIdentifier();
    if (alias === null) return false;
    this.usedAliases.add(alias);
    if (!this.reserveNode()) return false;
    this.lastNode = OTHER_NODE;
    return true;
  }

  private parseTextLiteral(): boolean {
    this.position += 1;
    while (this.position < this.source.length) {
      const current = this.source[this.position];
      if (current === "'") {
        this.position += 1;
        if (!this.reserveNode()) return false;
        this.lastNode = OTHER_NODE;
        return true;
      }
      if (current !== '\\') {
        this.position += 1;
        continue;
      }
      this.position += 1;
      const escaped = this.source[this.position];
      if (escaped === '\\' || escaped === "'" || escaped === 'n'
        || escaped === 'r' || escaped === 't') {
        this.position += 1;
        continue;
      }
      if (escaped !== 'u' || this.source[this.position + 1] !== '{') return this.reject();
      const start = this.position + 2;
      const end = this.source.indexOf('}', start);
      if (end === -1 || end === start) return this.reject();
      const hex = this.source.slice(start, end);
      if (!/^[0-9A-Fa-f]+$/.test(hex)) return this.reject();
      const codePoint = Number.parseInt(hex, 16);
      if (!Number.isSafeInteger(codePoint) || codePoint > 0x10ffff
        || (codePoint >= 0xd800 && codePoint <= 0xdfff)) return this.reject();
      this.position = end + 1;
    }
    return this.reject();
  }

  private parseDecimalLiteral(): boolean {
    const start = this.position;
    while (isDigit(this.source[this.position])) this.position += 1;
    if (this.source[this.position] === '.') {
      this.position += 1;
      const fractionStart = this.position;
      while (isDigit(this.source[this.position])) this.position += 1;
      if (this.position === fractionStart) return this.reject();
    }
    if (this.source[this.position] === 'e' || this.source[this.position] === 'E') {
      this.position += 1;
      if (this.source[this.position] === '+' || this.source[this.position] === '-') {
        this.position += 1;
      }
      const exponentStart = this.position;
      while (isDigit(this.source[this.position])) this.position += 1;
      if (this.position === exponentStart) return this.reject();
    }
    const token = this.source.slice(start, this.position);
    if (!validAdmittedDecimal(token) || !this.reserveNode()) return false;
    this.lastNode = { kind: 'decimal', token };
    return true;
  }

  private validExplicitRoundingScales(name: string, arguments_: readonly ParsedNode[]): boolean {
    const indices = name === 'divide' ? [2]
      : name === 'round' ? [1]
        : name === 'formatDecimal' ? [1, 2]
          : [];
    for (const index of indices) {
      const argument = arguments_[index];
      if (argument?.kind === 'decimal' && !explicitRoundingScaleWithinLimit(argument.token)) {
        return this.reject();
      }
    }
    return true;
  }

  private readIdentifier(): string | null {
    this.skipWhitespace();
    const first = this.source[this.position];
    if (first === undefined || !isIdentifierStart(first)) {
      this.reject();
      return null;
    }
    const start = this.position;
    this.position += 1;
    while (isIdentifierPart(this.source[this.position])) this.position += 1;
    return this.source.slice(start, this.position);
  }

  private take(token: string): boolean {
    this.skipWhitespace();
    if (!this.source.startsWith(token, this.position)) return false;
    this.position += token.length;
    return true;
  }

  private skipWhitespace(): void {
    while (this.source[this.position] === ' ' || this.source[this.position] === '\t'
      || this.source[this.position] === '\r' || this.source[this.position] === '\n') {
      this.position += 1;
    }
  }

  private reserveNode(): boolean {
    this.nodes += 1;
    return this.nodes <= 4096 || this.reject();
  }

  private reject(): false {
    this.failed = true;
    return false;
  }
}

function isIdentifierStart(value: string | undefined): boolean {
  return value !== undefined && /^[A-Za-z_]$/.test(value);
}

function isIdentifierPart(value: string | undefined): boolean {
  return value !== undefined && /^[A-Za-z0-9_]$/.test(value);
}

function isDigit(value: string | undefined): boolean {
  return value !== undefined && value >= '0' && value <= '9';
}

function validAdmittedDecimal(token: string): boolean {
  const facts = admittedDecimalFacts(token);
  return facts !== null
    && facts.precision <= 128
    && facts.scale >= -64
    && facts.scale <= 64;
}

function explicitRoundingScaleWithinLimit(token: string): boolean {
  const facts = admittedDecimalFacts(token);
  if (facts === null) return false;
  if (facts.scale > 0) return true;
  const value = BigInt(facts.digits) * (10n ** BigInt(-facts.scale));
  return value <= 64n;
}

function admittedDecimalFacts(
  token: string,
): { readonly precision: number; readonly scale: number; readonly digits: string } | null {
  const match = /^(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(token);
  if (!match) return null;
  const exponentToken = match[3] ?? '0';
  if (exponentToken.length > 9) return null;
  const exponent = Number(exponentToken);
  if (!Number.isSafeInteger(exponent)) return null;
  const coefficient = `${match[1] ?? ''}${match[2] ?? ''}`;
  if (/^0+$/.test(coefficient)) return { precision: 1, scale: 0, digits: '0' };
  const withoutLeadingZeroes = coefficient.replace(/^0+/, '');
  const trailingZeroes = withoutLeadingZeroes.length
    - withoutLeadingZeroes.replace(/0+$/, '').length;
  const digits = withoutLeadingZeroes.slice(0, withoutLeadingZeroes.length - trailingZeroes);
  return {
    precision: digits.length,
    scale: (match[2]?.length ?? 0) - exponent - trailingZeroes,
    digits,
  };
}
