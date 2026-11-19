package com.bbcinema.util;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CodeGeneratorTest {

    @RepeatedTest(20)
    void bookingCodeHasEightReadableCharacters() {
        String code = CodeGenerator.bookingCode();
        assertThat(code).hasSize(8).matches("[A-Z2-9]+").doesNotContain("0", "O", "1", "I", "L");
    }

    @Test
    void codesAreRandom() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            codes.add(CodeGenerator.bookingCode());
        }
        assertThat(codes).hasSize(1000);
    }

    @Test
    void blankToNull() {
        assertThat(TextUtil.blankToNull("  ")).isNull();
        assertThat(TextUtil.blankToNull(null)).isNull();
        assertThat(TextUtil.blankToNull(" abc ")).isEqualTo("abc");
    }
}
