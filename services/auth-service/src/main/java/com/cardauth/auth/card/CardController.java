package com.cardauth.auth.card;

import com.cardauth.auth.card.CardService.CardView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/cards")
public class CardController {

    public record CreateCard(@NotBlank String holder, @Positive long limit) {}
    public record ChangeLimit(@Positive long limit) {}

    private final CardService service;

    public CardController(CardService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CardView create(@Valid @RequestBody CreateCard body) { return service.create(body.holder(), body.limit()); }

    @GetMapping
    public List<CardView> list() { return service.list(); }

    @GetMapping("/{id}")
    public CardView get(@PathVariable String id) {
        return service.view(id).orElseThrow(() -> new CardService.NotFound(id));
    }

    @PutMapping("/{id}/limit")
    public CardView limit(@PathVariable String id, @Valid @RequestBody ChangeLimit body) {
        return service.changeLimit(id, body.limit());
    }

    @ExceptionHandler(CardService.NotFound.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String notFound(CardService.NotFound e) { return e.getMessage(); }
}
