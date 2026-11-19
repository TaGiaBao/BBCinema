package com.bbcinema.dto.request;

import java.util.Map;

/** Combo id -> quantity. A quantity of 0 removes the combo from the order. */
public record ComboSelection(Map<Long, Integer> quantities) {
}
