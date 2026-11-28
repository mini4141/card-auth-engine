package com.cardauth.auth.reconcile;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class AdminController {
    private final ReconcileService reconcile;

    public AdminController(ReconcileService reconcile) { this.reconcile = reconcile; }

    @GetMapping("/reconcile")
    public ReconcileService.Report reconcile() { return reconcile.reconcile(); }
}
