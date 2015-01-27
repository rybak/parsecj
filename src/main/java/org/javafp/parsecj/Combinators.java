package org.javafp.parsecj;

import org.javafp.data.IList;

import java.util.Optional;
import java.util.function.*;

import static org.javafp.parsecj.ConsumedT.*;
import static org.javafp.parsecj.Merge.mergeOk;
import static org.javafp.parsecj.Message.*;
import static org.javafp.parsecj.Reply.*;

/**
 * A set of parser combinator functions.
 * The Parser type along with retn &amp; bind constitute a monad.
 * This is a Java implementation of this paper:
 * http://research.microsoft.com/en-us/um/people/daan/download/papers/parsec-paper.pdf
 */
public abstract class Combinators {

    public static final Void UNIT = null;

    public static <S> S unit() {
        return null;
    }

    /**
     * Construct an Error Reply indicating the end of the input has been reached.
     */
    public static <S, A> Reply<S, A> endOfInput(State<S> state, String expected) {
        return Reply.<S, A>error(
            lazy(() -> Message.endOfInput(state.position(), expected))
        );
    }

    /**
     * Monadic return function.
     * @return a parser which returns the supplied value.
     */
    public static <S, A> Parser<S, A> retn(A x) {
        return state ->
            empty(
                Reply.ok(
                    x,
                    state,
                    lazy(() -> message(state.position()))
                )
            );
    }

    /**
     * Monadic bind function.
     * Bind chains two parsers by creating a parser which calls the first,
     * and if that succeeds the resultant value is passed to the
     * function argument to obtain a second parser, which is then invoked.
     * @param p a parser which is called first
     * @param f a function which is passed the result of the first parser if successful,
     *          and which returns a second parser
     * @return the combined parser
     */
    public static <S, A, B> Parser<S, B> bind(
            Parser<S, ? extends A> p,
            Function<A, Parser<S, B>> f) {
        return state -> {
            final ConsumedT<S, ? extends A> cons1 = p.apply(state);
            if (cons1.isConsumed()) {
                return consumed(() ->
                        cons1.getReply().<Reply<S, B>>match(
                            ok1 -> {
                                final ConsumedT<S, B> cons2 = f.apply(ok1.result).apply(ok1.rest);
                                return cons2.getReply();
                            },
                            error -> error.cast()
                        )
                );
            } else {
                return cons1.getReply().<ConsumedT<S, B>>match(
                    ok1 -> {
                        final ConsumedT<S, B> cons2 = f.apply(ok1.result).apply(ok1.rest);
                        if (cons2.isConsumed()) {
                            return cons2;
                        } else {
                            return cons2.getReply().match(
                                ok2 -> mergeOk(ok2.result, ok2.rest, ok1.msg, ok2.msg),
                                error -> Merge.<S, B>mergeError(ok1.msg, error.msg)
                            );
                        }
                    },
                    error -> empty(error.cast())
                );
            }
        };
    }

    /**
     * Apply the first parser, then apply the second parser and return the result.
     * Optimisation for bind(p, x -&gt; q) - i.e. discard x, the result of the first parser, p.
     */
    public static <S, A, B> Parser<S, B> then(Parser<S, ? extends A> p, Parser<S, B> q) {
        return state -> {
            final ConsumedT<S, ? extends A> cons1 = p.apply(state);
            if (cons1.isConsumed()) {
                return consumed(() ->
                        cons1.getReply().<Reply<S, B>>match(
                            ok1 -> {
                                final ConsumedT<S, B> cons2 = q.apply(ok1.rest);
                                return cons2.getReply();
                            },
                            error -> error.cast()
                        )
                );
            } else {
                return cons1.getReply().<ConsumedT<S, B>>match(
                    ok1 -> {
                        final ConsumedT<S, B> cons2 = q.apply(ok1.rest);
                        if (cons2.isConsumed()) {
                            return cons2;
                        } else {
                            return cons2.getReply().match(
                                ok2 -> mergeOk(ok2.result, ok2.rest, ok1.msg, ok2.msg),
                                error2 -> Merge.<S, B>mergeError(ok1.msg, error2.msg)
                            );
                        }
                    },
                    error -> cons1.cast()
                );
            }
        };
    }

    /**
     * A parser which always fails
     */
    public static <S, A> Parser<S, A> fail() {
        return state ->
            empty(
                Reply.error(
                    lazy(() -> message(state.position()))
                )
            );
    }

    /**
     * A parser which succeeds if the end of the input is reached.
     */
    public static <S> Parser<S, Void> eof() {
        return state -> {
            if (state.end()) {
                return empty(
                    Reply.ok(
                        state,
                        lazy(() -> message(state.position(), unit(), "EOF"))
                    )
                );
            } else {
                return empty(
                    Reply.<S, Void>error(
                        lazy(() -> message(state.position(), unit(), "EOF"))
                    )
                );
            }
        };
    }

    /**
     * A parser which succeeds if the next symbol passes the predicate.
     */
    public static <S> Parser<S, S> satisfy(Predicate<S> test) {
        return state -> {
            if (!state.end()) {
                final S s = state.current();
                if (test.test(s)) {
                    final State<S> newState = state.next();
                    return consumed(() -> ok(
                            s,
                            newState,
                            lazy(() -> message(state.position()))
                        )
                    );
                } else {
                    return empty(
                        error(
                            lazy(() -> message(state.position(), state.current(), "<test>"))
                        )
                    );
                }
            } else {
                return empty(endOfInput(state, "<test>"));
            }
        };
    }

    /**
     * A parser which succeeds if the next input symbol equals the supplied value.
     */
    public static <S> Parser<S, S> satisfy(S value) {
        return state -> {
            if (!state.end()) {
                if (state.current().equals(value)) {
                    final State<S> newState = state.next();
                    return consumed(() ->
                            ok(
                                state.current(),
                                newState,
                                lazy(() -> message(state.position()))
                            )
                    );
                } else {
                    return empty(
                        error(
                            lazy(() -> message(state.position(), state.current(), value.toString()))
                        )
                    );
                }
            } else {
                return empty(endOfInput(state, value.toString()));
            }
        };
    }

    /**
     * A parser which succeeds if the next input symbol equals the supplied value.
     * The parser replies with the second argument, result.
     * Equivalent to satisfy(value).then(retn(result))
     */
    public static <S, A> Parser<S, A> satisfy(S value, A result) {
        return state -> {
            if (!state.end()) {
                if (state.current().equals(value)) {
                    final State<S> newState = state.next();
                    return consumed(() ->
                            ok(
                                result,
                                newState,
                                lazy(() -> message(state.position()))
                            )
                    );
                } else {
                    return empty(
                        error(
                            lazy(() ->
                                message(state.position(), state.current(), value.toString())
                            )
                        )
                    );
                }
            } else {
                return empty(endOfInput(state, value.toString()));
            }
        };
    }

    /**
     * A parser which succeeds if either the first or second of the parser args succeeds.
     */
    public static <S, A> Parser<S, A> or(Parser<S, A> p, Parser<S, A> q) {
        return state -> {
            final ConsumedT<S, A> cons1 = p.apply(state);
            if (cons1.isConsumed()) {
                return cons1;
            } else {
                return cons1.getReply().match(
                    ok1 -> {
                        final ConsumedT<S, A> cons2 = q.apply(state);
                        if (cons2.isConsumed()) {
                            return cons2;
                        } else {
                            return mergeOk(ok1.result, ok1.rest, ok1.msg, cons2.getReply().msg);
                        }
                    },
                    error1 -> {
                        final ConsumedT<S, A> cons2 = q.apply(state);
                        if (cons2.isConsumed()) {
                            return cons2;
                        } else {
                            return cons2.getReply().match(
                                ok2 -> mergeOk(ok2.result, ok2.rest, error1.msg, ok2.msg),
                                error2 -> Merge.<S, A>mergeError(error1.msg, error2.msg)
                            );
                        }
                    }
                );
            }
        };
    }

    /**
     * Label a parser with a readable name for more meaningful error messages.
     */
    public static <S, A> Parser<S, A> label(Parser<S, A> p, String name) {
        return state -> {
            final ConsumedT<S, A> cons = p.apply(state);
            if (cons.isConsumed()) {
                return cons;
            } else {
                return cons.getReply().match(
                    ok -> empty(Reply.ok(ok.result, ok.rest, ok.msg.expect(name))),
                    error -> empty(Reply.error((error.msg.expect(name))))
                );
            }
        };
    }

    /**
     * A combinator which turns a parser which consumes input
     * into one which doesn't consume input if it fails.
     * This allows the implementation of LL(∞) grammars.
     */
    public static <S, A> Parser<S, A> attempt(Parser<S, A> p) {
        return state -> {
            final ConsumedT<S, A> cons = p.apply(state);
            if (cons.isConsumed()) {
                return cons.getReply().match(
                    ok -> cons,
                    error -> empty(error)
                );
            } else {
                return cons;
            }
        };
    }

    /**
     * choice tries to apply the parsers in the list ps in order,
     * until one of them succeeds. Returns the value of the succeeding
     * parser
     */
    public static <S, A> Parser<S, A> choice(IList<Parser<S, A>> ps) {
        if (ps.tail().isEmpty()) {
            return ps.head();
        } else {
            return or(ps.head(), choice(ps.tail()));
        }
    }

    /**
     * choice tries to apply the parsers in the list ps in order,
     * until one of them succeeds. It returns the value of the succeeding
     * parser
     */
    public static <S, A> Parser<S, A> choice(Parser<S, A>... ps) {
        return choice(IList.list(ps));
    }

    /**
     * A parser which attempts parser p first and if it fails then return x.
     */
    public static <S, A> Parser<S, A> option(Parser<S, A> p, A x) {
        return or(p, retn(x));
    }

    /**
     * A parser for optional values.
     */
    public static <S, A> Parser<S, Optional<A>> optionalOpt(Parser<S, A> p) {
        return option(
            p.bind(x -> retn(Optional.of(x))),
            Optional.empty()
        );
    }

    /**
     * A parser for optional values, which throws the result away.
     */
    public static <S, A> Parser<S, Void> optional(Parser<S, A> p) {
        return or(bind(p, x -> retn(UNIT)), retn(UNIT));
    }

    /**
     * A parser which parses an OPEN symbol, then applies parser p, then parses a CLOSE symbol,
     * and returns the result of p.
     */
    public static <S, A, OPEN, CLOSE> Parser<S, A> between(
            Parser<S, OPEN> open,
            Parser<S, CLOSE> close,
            Parser<S, A> p) {
        return then(open, bind(p, a -> then(close, retn(a))));
    }

    /**
     * A parser for a list of zero or more values of the same type.
     */
    public static <S, A> Parser<S, IList<A>> many(Parser<S, A> p) {
        return manyAcc(p, IList.empty());
    }

    /**
     * A parser for a list of one or more values of the same type.
     */
    public static <S, A> Parser<S, IList<A>> many1(Parser<S, A> p) {
        return bind(p, x -> manyAcc(p, IList.list(x)));
    }

    private static <S, A> Parser<S, IList<A>> manyAcc(Parser<S, A> p, IList<A> acc) {
        return or(bind(p, x -> manyAcc(p, acc.add(x))), retn(acc.reverse()));
    }

    /**
     * A parser which applies the parser p zero or more times, throwing away the result.
     */
    public static <S, A> Parser<S, Void> skipMany(Parser<S, A> p) {
        return or(bind(p, x -> skipMany(p)), retn(UNIT));
    }

    /**
     * A parser which applies the parser p one or more times, throwing away the result.
     */
    public static <S, A> Parser<S, Void> skipMany1(Parser<S, A> p) {
        return then(p, skipMany(p));
    }

    /**
     * A parser which parses zero or more occurrences of p, separated by sep,
     * and returns a list of values returned by p.
     */
    public static <S, A, SEP> Parser<S, IList<A>> sepBy(
            Parser<S, A> p,
            Parser<S, SEP> sep) {
        return or(sepBy1(p, sep), retn(IList.empty()));
    }

    /**
     * A parser which parses one or more occurrences of p, separated by sep,
     * and returns a list of values returned by p.
     */
    public static <S, A, SEP> Parser<S, IList<A>> sepBy1(
            Parser<S, A> p,
            Parser<S, SEP> sep) {
        return bind(
            p,
            x -> bind(
                many(then(sep, p)),
                xs -> retn(xs.add(x))
            )
        );
    }

    /**
     * A parser which parses zero or more occurrences of p, separated by sep,
     * and optionally ended by sep,
     * and returns a list of values returned by p.
     */
    public static <S, A, SEP> Parser<S, IList<A>> sepEndBy(
            Parser<S, A> p,
            Parser<S, SEP> sep) {
        return or(sepEndBy1(p, sep), retn(IList.empty()));
    }

    /**
     * A parser which parses one or more occurrences of p, separated by sep,
     * and optionally ended by sep,
     * and returns a list of values returned by p.
     */
    public static <S, A, SEP> Parser<S, IList<A>> sepEndBy1(
            Parser<S, A> p,
            Parser<S, SEP> sep) {
        return bind(
            p,
            x -> or(
                then(
                    sep,
                    bind(
                        sepEndBy(p, sep),
                        xs -> retn(xs.add(x))
                    )
                ),
                retn(IList.list(x))
            )
        );
    }

    /**
     * A parser which parses zero or more occurrences of p, separated and ended by sep,
     * and ended by sep,
     * and returns a list of values returned by p.
     */
    public static <S, A, SEP> Parser<S, IList<A>> endBy(
            Parser<S, A> p,
            Parser<S, SEP> sep) {
        return many(bind(p, x -> then(sep, retn(x))));
    }

    /**
     * A parser which parses one or more occurrences of p, separated by sep,
     * and ended by sep,
     * and returns a list of values returned by p.
     */
    public static <S, A, SEP> Parser<S, IList<A>> endBy1(
            Parser<S, A> p,
            Parser<S, SEP> sep) {
        return many1(bind(p, x -> then(sep, retn(x))));
    }

    /**
     * A parser which applies parser p n times.
     */
    public static <S, A> Parser<S, IList<A>> count(
            Parser<S, A> p,
            int n) {
        return countAcc(p, n, IList.empty());
    }

    private static <S, A> Parser<S, IList<A>> countAcc(
            Parser<S, A> p,
            int n,
            IList<A> acc) {
        if (n == 0) {
            return retn(acc.reverse());
        } else {
            return bind(p, x -> countAcc(p, n - 1, acc.add(x)));
        }
    }

    /**
     * A parser for an operand followed by zero or more operands (p) separated by right-associative operators (op).
     * If there are zero operands then x is returned.
     */
    public static <S, A> Parser<S, A> chainr(
            Parser<S, A> p,
            Parser<S, BinaryOperator<A>> op,
            A x) {
        return or(chainr1(p, op), retn(x));
    }

    /**
     * A parser for an operand followed by zero or more operands (p) separated by left-associative operators (op).
     * If there are zero operands then x is returned.
     */
    public static <S, A> Parser<S, A> chainl(
            Parser<S, A> p,
            Parser<S, BinaryOperator<A>> op,
            A x) {
        return or(chainl1(p, op), retn(x));
    }

    /**
     * A parser for an operand followed by one or more operands (p) separated by right-associative operators (op).
     */
    public static <S, A> Parser<S, A> chainr1(
            Parser<S, A> p,
            Parser<S, BinaryOperator<A>> op) {
        return scanr1(p, op);
    }

    private static <S, A> Parser<S, A> scanr1(
            Parser<S, A> p,
            Parser<S, BinaryOperator<A>> op) {
        return bind(p, x -> restr1(p, op, x));
    }

    private static <S, A> Parser<S, A> restr1(
            Parser<S, A> p,
            Parser<S, BinaryOperator<A>> op,
            A x) {
        return or(
            bind(
                op,
                f -> bind(
                    scanr1(p, op),
                    y -> retn(f.apply(x, y))
                )
            ),
            retn(x)
        );
    }

    /**
     * A parser for an operand followed by one or more operands (p) separated by operators (op).
     * This parser can for example be used to eliminate left recursion which typically occurs in expression grammars.
     */
    public static <S, A> Parser<S, A> chainl1(
            Parser<S, A> p,
            Parser<S, BinaryOperator<A>> op) {
        return bind(p, x -> restl1(p, op, x));
    }

    private static <S, A> Parser<S, A> restl1(
            Parser<S, A> p,
            Parser<S, BinaryOperator<A>> op,
            A x) {
        return or(
            bind(
                op,
                f -> bind(
                    p,
                    y -> restl1(
                        p,
                        op,
                        f.apply(x, y)
                    )
                )
            ),
            retn(x)
        );
    }
}
