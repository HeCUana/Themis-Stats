package dev.themisstats.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

/**
 * An argument that reads one whitespace separated token, whatever characters it contains.
 *
 * <p>Brigadier's own {@code StringArgumentType#word()} only accepts letters, digits and
 * {@code _ - . +}. A resource location such as {@code minecraft:stone} therefore cannot be passed
 * to it: the reader stops at the colon and the parser reports "found trailing data".
 *
 * <p>This type reads the token verbatim, which is what the category and the dimension need.
 * It deliberately accepts anything but whitespace, so it must only be used where the value is
 * looked up in a table and never concatenated into a statement.</p>
 */
public final class TokenArgumentType implements ArgumentType<String> {

    /** The one instance of this argument type. */
    public static final TokenArgumentType INSTANCE = new TokenArgumentType();

    /** The error for an empty token. */
    private static final com.mojang.brigadier.exceptions.SimpleCommandExceptionType EMPTY =
            new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                    new com.mojang.brigadier.LiteralMessage("Expected a value"));

    private TokenArgumentType() {
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
            reader.skip();
        }
        if (reader.getCursor() == start) {
            throw EMPTY.createWithContext(reader);
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    @Override
    public String toString() {
        return "token";
    }
}
