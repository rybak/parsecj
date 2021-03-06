package org.javafp.parsecj;

import org.javafp.data.Unit;

import java.util.function.*;
import java.util.regex.*;

import static org.javafp.parsecj.Combinators.*;
import static org.javafp.parsecj.ConsumedT.empty;

/**
 * Parser combinators to be used with Character streams.
 */
public abstract class Text {

    /**
     * Helper methods to create a ConsumedT response.
     */
    private static <I, A> ConsumedT<I, A> consError(boolean consumed, Input<I> input, String expected) {
        final Message<I> msg = Message.lazy(() -> Message.of(input.position(), input.current(), expected));
        return ConsumedT.of(consumed, () -> Reply.error(msg));
    }

    private static <I, A> ConsumedT<I, A> eofError(boolean consumed, Input<I> input, String expected) {
        return ConsumedT.of(consumed, () -> endOfInput(input, expected));
    }

    /**
     * A parser which parses an alphabetic character.
     */
    public static final Parser<Character, Character> alpha =
        satisfy((Character c) -> Character.isAlphabetic(c)).label("alpha");

    /**
     * A parser which parses a numeric character, i.e. a digit.
     */
    public static final Parser<Character, Character> digit =
        satisfy((Character c) -> Character.isDigit(c)).label("digit");

    /**
     * A parser which parses space.
     */
    public static final Parser<Character, Character> space =
        satisfy((Character c) -> Character.isSpaceChar(c)).label("space");

    /**
     * A parser which parses whitespace.
     */
    public static final Parser<Character, Character> wspace =
        satisfy((Character c) -> Character.isWhitespace(c)).label("wspace");

    /**
     * A parser which skips whitespace.
     */
    public static final Parser<Character, Unit> wspaces = skipMany(wspace);

    /**
     * A parser which accepts only the specified char.
     * @param c     the character
     * @return      the parser
     */
    public static Parser<Character, Character> chr(char c) {
        return satisfy(c);
    }

    private static final String INTEGER_REGEX = "-?\\d+";
    private static final String DOUBLE_REGEX = "-?(\\d+(\\.\\d*)?|\\d*\\.\\d+)([eE][+-]?\\d+)?[fFdD]?";
    private static final Parser<Character, String> matchDouble = regex(DOUBLE_REGEX);

    /**
     * A parser which parses a signed {@link Integer}.
     */
    public static final Parser<Character, Integer> intr =
        bind(
            regex(INTEGER_REGEX),
            s -> safeRetn(Integer::valueOf, s, "integer")
        ).label("integer");

    /**
     * A parser which parses a signed {@link Long}.
     */
    public static final Parser<Character, Long> lng =
        bind(
            regex(INTEGER_REGEX),
            s -> safeRetn(Long::valueOf, s, "long")
        ).label("long");

    /**
     * A parser which parses a signed {@link Double}.
     */
    public static final Parser<Character, Double> dble =
        bind(
            matchDouble,
            s -> safeRetn(Double::valueOf, s, "double")
        ).label("double");

    /**
     * A parser which will parse a number into either a Double or a Long.
     */
    public static final Parser<Character, Number> number =
        bind(
            matchDouble,
            s -> safeRetn(
                ds -> {
                    final Double d = Double.valueOf(ds);
                    final long l = d.longValue();
                    if (((double)l) == d) {
                        return (Number)l;
                    } else {
                        return (Number)d;
                    }
                },
                s,
                "number"
            )
        ).label("number");

    // Variant of retn which translates exceptions into ConsumedT errors.
    private static <A> Parser<Character, A> safeRetn(Function<String, A> f, String s, String expected) {
        return input -> {
            try {
                final A val = f.apply(s);
                return empty(
                    Reply.ok(
                        val,
                        input,
                        Message.lazy(() -> Message.of(input.position()))
                    )
                );
            } catch (Exception ex) {
                return ConsumedT.empty(
                    Reply.error(Message.lazy(() -> Message.of(input.position(), input.current(), expected)))
                );
            }
        };
    }

    /**
     * A parser which only accepts the specified string.
     * @param value the string
     * @return      the parser
     */
    public static Parser<Character, String> string(String value) {
        Parser<Character, String> p = state -> {
            if (state.end()) {
                return ConsumedT.empty(endOfInput(state, value));
            }

            boolean consumed = false;
            char c = state.current();
            int i = 0;
            while (c == value.charAt(i)) {
                consumed = true;
                state = state.next();
                ++i;
                if (i == value.length()) {
                    final Input<Character> tail = state;
                    return ConsumedT.consumed(
                        () -> Reply.ok(
                            value,
                            tail,
                            Message.lazy(() -> Message.of(tail.position()))
                        )
                    );
                } else if (state.end()) {
                    return eofError(consumed, state, value);
                }
                c = state.current();
            }

            return consError(consumed, state, "\"" + value + '"');
        };

        return p.label(value);
    }

    /**
     * A parser which parses an alphanumeric string.
     */
    public static final Parser<Character, String> alphaNum =
        state -> {
            if (state.end()) {
                return ConsumedT.empty(endOfInput(state, "alphaNum"));
            }

            char c = state.current();
            if (!Character.isAlphabetic(c) && !Character.isDigit(c)) {
                final Input<Character> tail = state;
                return ConsumedT.empty(
                    Reply.error(
                        Message.lazy(() -> Message.of(tail.position(), tail.current(), "alphaNum"))
                    )
                );
            }

            final StringBuilder sb = new StringBuilder();
            do {
                sb.append(c);
                state = state.next();
                if (state.end()) {
                    break;
                }
                c = state.current();

            } while (Character.isAlphabetic(c) || Character.isDigit(c));

            final Input<Character> tail = state;
            return ConsumedT.consumed(
                () -> Reply.ok(
                    sb.toString(),
                    tail,
                    Message.lazy(() -> Message.of(tail.position()))
                )
            );
        };

    /**
     * A parser which accepts a string which matches the supplied regex.
     * @param regex the regular expression
     * @return      the parser
     */
    public static Parser<Character, String> regex(String regex) {
        final Pattern pattern = Pattern.compile(regex);
        return state -> {
            final CharSequence cs;
            if (state instanceof CharInput) {
                final CharInput charState = (CharInput) state;
                cs = charState.getCharSequence();
            } else {
                throw new RuntimeException("regex only supported on CharState inputs");
            }

            final Matcher matcher = pattern.matcher(cs);

            final Message<Character> msg = Message.lazy(
                () -> Message.of(state.position(), state.current(), "Regex('" + regex + "')")
            );

            if (matcher.lookingAt()) {
                final int end = matcher.end();
                final String str = cs.subSequence(0, end).toString();
                return ConsumedT.consumed(() -> Reply.ok(str, state.next(end), msg));
            } else {
                return ConsumedT.empty(Reply.error(msg));
            }
        };
    }

    /**
     * A parser which parses a string between a pair of enclosing characters.
     */
    public static Parser<Character, String> strBetween(
            Parser<Character, Character> open,
            Parser<Character, Character> close) {
        return state -> {
            if (state.end()) {
                return ConsumedT.empty(endOfInput(state, "strBetween"));
            }

            // Parse the opening enclose char.
            final ConsumedT<Character, Character> cons = open.apply(state);
            if (cons.getReply().isError()) {
                return cons.cast();
            }

            Input<Character> input2 = ((Reply.Ok<Character, Character>)cons.getReply()).rest;

            final StringBuilder sb = new StringBuilder();

            do {
                if (input2.end()) {
                    final Input<Character> input3 = input2;
                    return ConsumedT.consumed(
                        () -> endOfInput(input3, "<char>")
                    );
                }

                // Attempt to parse the closing enclose char.
                final ConsumedT<Character, Character> cons2 = close.apply(input2);
                if (cons2.getReply().isOk()) {
                    input2 = ((Reply.Ok<Character, Character>)cons2.getReply()).rest;
                    break;
                }

                sb.append(input2.current());
                input2 = input2.next();
            } while (true);

            final Input<Character> tail = input2;
            return ConsumedT.consumed(
                () -> Reply.ok(
                    sb.toString(),
                    tail,
                    Message.lazy(() -> Message.of(tail.position()))
                )
            );
        };
    }
}
