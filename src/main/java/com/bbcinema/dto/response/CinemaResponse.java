package com.bbcinema.dto.response;

import com.bbcinema.entity.Cinema;

public record CinemaResponse(Long id, String name, String address, String phone) {

    public static CinemaResponse from(Cinema c) {
        return new CinemaResponse(c.getId(), c.getName(), c.getAddress(), c.getPhone());
    }
}
