package com.bbcinema.dto.response;

public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds, String email, String role) {
}
