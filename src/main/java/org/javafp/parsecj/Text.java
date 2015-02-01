package org.javafp.parsecj;

import org.javafp.data.Unit;

import java.util.regex.*;

import static org.javafp.parsecj.Combinators.*;

/**
 * Parser combinators to be used with Character streams.
 */
public abstract class Text {

    /**
     * Helper method to create a ConsumedT response.
     */
    private static <S, A> ConsumedT<S, A> consError(boolean consumed, State<S> state, String expected) {
        final Message<S> msg = Message.lazy(() -> Message.of(state.position(), state.current(), expected));
        return ConsumedT.of(consumed, () -> Reply.error(msg));
    }

    private static <S, A> ConsumedT<S, A> eofError(boolean consumed, State<S> state, String expected) {
        return ConsumedT.of(consumed, () -> endOfInput(state, expected));
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

    /**
     * A parser which parses a signed integer.
     */
    public static final Parser<Character, Integer> intr =
        regex("-?\\d+")
            .label("integer")
            .bind(s -> retn(Integer.valueOf(s)));

    /**
     * A parser which parses a signed double.
     */
    public static final Parser<Character, Double> dble =
        regex("-?(\\d+(\\.\\d*)?|\\d*\\.\\d+)([eE][+-]?\\d+)?[fFdD]?")
            .label("double")
            .bind(s -> retn(Double.valueOf(s)));

    /**
     * A parser which only accepts the specified string.
     * @param value the string
     * @return      the parser
     */
    public static Parser<Character, String> string(String value) {
        return state -> {
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
                    final State<Character> tail = state;
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
                final State<Character> tail = state;
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

            final State<Character> tail = state;
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
            if (state instanceof CharState) {
                final CharState charState = (CharState) state;
                cs = charState.getCharSequence();
            } else {
                throw new RuntimeException("regex only supported on CharState");
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
}
