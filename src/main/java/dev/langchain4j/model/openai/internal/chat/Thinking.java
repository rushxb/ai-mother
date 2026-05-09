package dev.langchain4j.model.openai.internal.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class Thinking {

    @JsonProperty
    private final String type;

    private Thinking(String type) {
        this.type = type;
    }

    public String type() {
        return type;
    }

    public static Thinking disabled() {
        return new Thinking("disabled");
    }

    @Override
    public boolean equals(Object another) {
        if (this == another) {
            return true;
        }
        return another instanceof Thinking thinking
                && Objects.equals(type, thinking.type);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(type);
    }

    @Override
    public String toString() {
        return "Thinking{"
                + "type=" + type
                + "}";
    }
}
