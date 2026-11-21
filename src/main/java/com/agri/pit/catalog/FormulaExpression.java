package com.agri.pit.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A deliberately small, side-effect free expression language for feature formulas.
 * <p>
 * Supported grammar:
 * <pre>
 *   expr    := term (('+' | '-') term)*
 *   term    := factor (('*' | '/') factor)*
 *   factor  := ('-' factor) | number | identifier | '(' expr ')' | identifier '(' args ')'
 *   args    := expr (',' expr)*
 * </pre>
 * Identifiers resolve to aggregate variables supplied by the run engine (for example
 * {@code ndvi_mean}, {@code rainfall_sum}). Functions are limited to a fixed allow-list,
 * so formula authors cannot reach outside the evaluated dataset.
 */
public final class FormulaExpression {

    private static final Set<String> FUNCTIONS =
            Set.of("min", "max", "abs", "round", "floor", "ceil", "clamp", "coalesce");

    private final Node root;
    private final List<String> variables;

    private FormulaExpression(Node root, List<String> variables) {
        this.root = root;
        this.variables = List.copyOf(variables);
    }

    public static FormulaExpression compile(String formula) {
        if (formula == null || formula.isBlank()) {
            throw new IllegalArgumentException("formula is blank");
        }
        Parser parser = new Parser(formula);
        Node root = parser.parseExpression();
        parser.expectEnd();
        return new FormulaExpression(root, parser.variables);
    }

    public List<String> variables() {
        return variables;
    }

    public double evaluate(Map<String, Double> values) {
        return root.eval(values);
    }

    public double evaluate(Map<String, Double> values, double fallback) {
        try {
            return root.eval(values);
        } catch (MissingVariableException e) {
            return fallback;
        }
    }

    interface Node {
        double eval(Map<String, Double> values);
    }

    static final class MissingVariableException extends RuntimeException {
        MissingVariableException(String name) {
            super("variable not available: " + name);
        }
    }

    private static final class NumberNode implements Node {
        private final double value;

        NumberNode(double value) {
            this.value = value;
        }

        @Override
        public double eval(Map<String, Double> values) {
            return value;
        }
    }

    private static final class VariableNode implements Node {
        private final String name;

        VariableNode(String name) {
            this.name = name;
        }

        @Override
        public double eval(Map<String, Double> values) {
            Double value = values.get(name);
            if (value == null || value.isNaN()) {
                throw new MissingVariableException(name);
            }
            return value;
        }
    }

    private static final class NegateNode implements Node {
        private final Node child;

        NegateNode(Node child) {
            this.child = child;
        }

        @Override
        public double eval(Map<String, Double> values) {
            return -child.eval(values);
        }
    }

    private static final class BinaryNode implements Node {
        private final char op;
        private final Node left;
        private final Node right;

        BinaryNode(char op, Node left, Node right) {
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        public double eval(Map<String, Double> values) {
            double l = left.eval(values);
            double r = right.eval(values);
            return switch (op) {
                case '+' -> l + r;
                case '-' -> l - r;
                case '*' -> l * r;
                case '/' -> r == 0d ? Double.NaN : l / r;
                default -> throw new IllegalStateException("unexpected operator " + op);
            };
        }
    }

    private static final class FunctionNode implements Node {
        private final String name;
        private final List<Node> args;

        FunctionNode(String name, List<Node> args) {
            this.name = name;
            this.args = args;
        }

        @Override
        public double eval(Map<String, Double> values) {
            double[] a = new double[args.size()];
            for (int i = 0; i < a.length; i++) {
                a[i] = args.get(i).eval(values);
            }
            return switch (name) {
                case "min" -> {
                    requireArity(name, a, 1, Integer.MAX_VALUE);
                    double m = a[0];
                    for (double v : a) {
                        m = Math.min(m, v);
                    }
                    yield m;
                }
                case "max" -> {
                    requireArity(name, a, 1, Integer.MAX_VALUE);
                    double m = a[0];
                    for (double v : a) {
                        m = Math.max(m, v);
                    }
                    yield m;
                }
                case "abs" -> {
                    requireArity(name, a, 1, 1);
                    yield Math.abs(a[0]);
                }
                case "round" -> {
                    requireArity(name, a, 1, 2);
                    if (a.length == 1) {
                        yield Math.round(a[0]);
                    }
                    double factor = Math.pow(10, a[1]);
                    yield Math.round(a[0] * factor) / factor;
                }
                case "floor" -> {
                    requireArity(name, a, 1, 1);
                    yield Math.floor(a[0]);
                }
                case "ceil" -> {
                    requireArity(name, a, 1, 1);
                    yield Math.ceil(a[0]);
                }
                case "clamp" -> {
                    requireArity(name, a, 3, 3);
                    yield Math.max(a[1], Math.min(a[2], a[0]));
                }
                case "coalesce" -> {
                    requireArity(name, a, 1, Integer.MAX_VALUE);
                    for (double v : a) {
                        if (!Double.isNaN(v)) {
                            yield v;
                        }
                    }
                    yield Double.NaN;
                }
                default -> throw new IllegalStateException("unknown function " + name);
            };
        }

        private static void requireArity(String name, double[] args, int min, int max) {
            if (args.length < min || args.length > max) {
                throw new IllegalArgumentException("function " + name + " arity mismatch");
            }
        }
    }

    private static final class Parser {
        private final String src;
        private int pos;
        private final List<String> variables = new ArrayList<>();

        Parser(String src) {
            this.src = src;
        }

        Node parseExpression() {
            Node node = parseTerm();
            while (true) {
                skipWs();
                if (peek() == '+') {
                    pos++;
                    node = new BinaryNode('+', node, parseTerm());
                } else if (peek() == '-') {
                    pos++;
                    node = new BinaryNode('-', node, parseTerm());
                } else {
                    return node;
                }
            }
        }

        private Node parseTerm() {
            Node node = parseFactor();
            while (true) {
                skipWs();
                if (peek() == '*') {
                    pos++;
                    node = new BinaryNode('*', node, parseFactor());
                } else if (peek() == '/') {
                    pos++;
                    node = new BinaryNode('/', node, parseFactor());
                } else {
                    return node;
                }
            }
        }

        private Node parseFactor() {
            skipWs();
            char c = peek();
            if (c == '-') {
                pos++;
                return new NegateNode(parseFactor());
            }
            if (c == '+') {
                pos++;
                return parseFactor();
            }
            if (c == '(') {
                pos++;
                Node node = parseExpression();
                skipWs();
                expect(')');
                return node;
            }
            if (Character.isDigit(c) || c == '.') {
                return parseNumber();
            }
            if (Character.isLetter(c) || c == '_') {
                return parseIdentifier();
            }
            throw new IllegalArgumentException("unexpected character '" + c + "' at position " + pos);
        }

        private Node parseNumber() {
            int start = pos;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                pos++;
            }
            String token = src.substring(start, pos);
            try {
                return new NumberNode(Double.parseDouble(token));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid number literal: " + token);
            }
        }

        private Node parseIdentifier() {
            int start = pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                pos++;
            }
            String token = src.substring(start, pos).toLowerCase(Locale.ROOT);
            skipWs();
            if (peek() == '(') {
                if (!FUNCTIONS.contains(token)) {
                    throw new IllegalArgumentException("unsupported function: " + token);
                }
                pos++;
                List<Node> args = new ArrayList<>();
                skipWs();
                if (peek() != ')') {
                    args.add(parseExpression());
                    skipWs();
                    while (peek() == ',') {
                        pos++;
                        args.add(parseExpression());
                        skipWs();
                    }
                }
                expect(')');
                if (!token.equals("coalesce") && !token.equals("min") && !token.equals("max")) {
                    int expected = arity(token);
                    if (args.size() != expected) {
                        throw new IllegalArgumentException("function " + token + " expects " + expected
                                + " argument(s), got " + args.size());
                    }
                }
                return new FunctionNode(token, args);
            }
            if (!variables.contains(token)) {
                variables.add(token);
            }
            return new VariableNode(token);
        }

        private int arity(String fn) {
            return switch (fn) {
                case "abs", "floor", "ceil" -> 1;
                case "round" -> 1;
                case "clamp" -> 3;
                default -> 1;
            };
        }

        void expectEnd() {
            skipWs();
            if (pos != src.length()) {
                throw new IllegalArgumentException("unexpected trailing input at position " + pos);
            }
        }

        private void expect(char c) {
            if (peek() != c) {
                throw new IllegalArgumentException("expected '" + c + "' at position " + pos);
            }
            pos++;
        }

        private char peek() {
            return pos < src.length() ? src.charAt(pos) : '\0';
        }

        private void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
    }
}
