package com.bbcinema.web;

import com.bbcinema.service.DemoMailbox;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/** /demo/mailbox: the e-mails the app sent, for demos without a mail account. 404 when the mailbox is off. */
@Controller
@RequiredArgsConstructor
public class DemoMailboxController {

    private final DemoMailbox mailbox;

    @GetMapping("/demo/mailbox")
    public String list(@RequestParam(required = false) Long id, Model model) {
        requireEnabled();
        var mails = mailbox.all();
        model.addAttribute("mails", mails);
        model.addAttribute("selected", id != null ? mailbox.find(id).orElse(null) : mails.stream().findFirst().orElse(null));
        return "demo/mailbox";
    }

    /** The mail itself, shown in an iframe so its styles do not mix with the page. */
    @GetMapping(value = "/demo/mailbox/{id}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> show(@PathVariable long id) {
        requireEnabled();
        return mailbox.find(id)
                .map(m -> ResponseEntity.ok().contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8)).body(m.html()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void requireEnabled() {
        if (!mailbox.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
