package com.bbcinema.service;

import com.bbcinema.dto.request.CinemaForm;
import com.bbcinema.entity.Cinema;
import com.bbcinema.exception.NotFoundException;
import com.bbcinema.repository.CinemaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CinemaService {

    private final CinemaRepository cinemaRepository;

    public List<Cinema> findActive() {
        return cinemaRepository.findByActiveTrueOrderByNameAsc();
    }

    public List<Cinema> findAll() {
        return cinemaRepository.findAllByOrderByNameAsc();
    }

    public Cinema getById(Long id) {
        return cinemaRepository.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy rạp"));
    }

    /**
     * Resolves the cinema the visitor picked (stored in a cookie). Falls back to the first
     * active cinema when the cookie is missing or points to a closed cinema.
     */
    public Optional<Cinema> resolveSelected(Long cinemaId) {
        List<Cinema> active = findActive();
        if (cinemaId != null) {
            Optional<Cinema> match = active.stream().filter(c -> c.getId().equals(cinemaId)).findFirst();
            if (match.isPresent()) {
                return match;
            }
        }
        return active.stream().findFirst();
    }

    @Transactional
    public Cinema save(CinemaForm form) {
        Cinema cinema = form.getId() == null ? new Cinema() : getById(form.getId());
        cinema.setName(form.getName().trim());
        cinema.setAddress(form.getAddress().trim());
        cinema.setPhone(form.getPhone());
        cinema.setActive(form.isActive());
        return cinemaRepository.save(cinema);
    }

    public CinemaForm toForm(Cinema cinema) {
        CinemaForm form = new CinemaForm();
        form.setId(cinema.getId());
        form.setName(cinema.getName());
        form.setAddress(cinema.getAddress());
        form.setPhone(cinema.getPhone());
        form.setActive(cinema.isActive());
        return form;
    }
}
