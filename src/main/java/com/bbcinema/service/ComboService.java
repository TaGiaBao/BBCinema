package com.bbcinema.service;

import com.bbcinema.dto.request.ComboForm;
import com.bbcinema.entity.Combo;
import com.bbcinema.exception.NotFoundException;
import com.bbcinema.repository.ComboRepository;
import com.bbcinema.util.TextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ComboService {

    private final ComboRepository comboRepository;
    private final FileStorageService fileStorageService;

    public List<Combo> findActive() {
        return comboRepository.findByActiveTrueOrderByPriceAsc();
    }

    public List<Combo> findAll() {
        return comboRepository.findAllByOrderByIdAsc();
    }

    public Combo getById(Long id) {
        return comboRepository.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy combo"));
    }

    @Transactional
    public Combo save(ComboForm form, MultipartFile image) {
        Combo combo = form.getId() == null ? new Combo() : getById(form.getId());
        combo.setName(form.getName().trim());
        combo.setDescription(TextUtil.blankToNull(form.getDescription()));
        combo.setPrice(form.getPrice());
        combo.setActive(form.isActive());
        String path = fileStorageService.storeImage(image, "combos");
        if (path != null) {
            combo.setImagePath(path);
        }
        return comboRepository.save(combo);
    }

    public ComboForm toForm(Combo combo) {
        ComboForm form = new ComboForm();
        form.setId(combo.getId());
        form.setName(combo.getName());
        form.setDescription(combo.getDescription());
        form.setPrice(combo.getPrice());
        form.setActive(combo.isActive());
        form.setImagePath(combo.getImagePath());
        return form;
    }
}
