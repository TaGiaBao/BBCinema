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

    @RepeatedTest(20)
    void memberCodeIsBbPlusEightReadableCharacters() {
        String code = CodeGenerator.memberCode();
        assertThat(code).hasSize(10).startsWith("BB").matches("BB[A-Z2-9]{8}");
        assertThat(code.substring(2)).doesNotContain("0", "O", "1", "I", "L");
        assertThat(CodeGenerator.isMemberCode(code)).isTrue();
    }

    @Test
    void memberCodeShape() {
        assertThat(CodeGenerator.isMemberCode("BB7KQ2MXPA")).isTrue();
        assertThat(CodeGenerator.isMemberCode("bb7kq2mxpa")).isFalse(); // callers upper-case first
        assertThat(CodeGenerator.isMemberCode("BB7KQ2MXP")).isFalse();
        assertThat(CodeGenerator.isMemberCode("BB7KQ2MXP0")).isFalse();
        assertThat(CodeGenerator.isMemberCode("K7M2QX9P")).isFalse(); // a booking code is not a member code
        assertThat(CodeGenerator.isMemberCode(null)).isFalse();
    }

    @Test
    void blankToNull() {
        assertThat(TextUtil.blankToNull("  ")).isNull();
        assertThat(TextUtil.blankToNull(null)).isNull();
        assertThat(TextUtil.blankToNull(" abc ")).isEqualTo("abc");
    }
}
