package dev.backed.web;

import dev.backed.investigate.AiProvider;
import dev.backed.investigate.Investigator;
import dev.backed.investigate.ProposalService;
import dev.backed.ledger.BusinessDay;
import dev.backed.paypal.PayPalGateway;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.LocalDate;
import java.util.stream.Collectors;

/**
 * Values every page's layout needs: who is signed in, their roles, the business date, the current
 * path, whether PayPal and the AI investigator are configured, and which model the investigator uses.
 */
@ControllerAdvice
public class LayoutAdvice {

    private final BusinessDay businessDay;
    private final ObjectProvider<PayPalGateway> paypal;
    private final ObjectProvider<Investigator> investigator;

    public LayoutAdvice(BusinessDay businessDay, ObjectProvider<PayPalGateway> paypal,
                        ObjectProvider<Investigator> investigator) {
        this.businessDay = businessDay;
        this.paypal = paypal;
        this.investigator = investigator;
    }

    @ModelAttribute("paypalConfigured")
    public boolean paypalConfigured() {
        PayPalGateway gateway = paypal.getIfAvailable();
        return gateway != null && gateway.configured();
    }

    @ModelAttribute("aiConfigured")
    public boolean aiConfigured() {
        Investigator i = investigator.getIfAvailable();
        return i != null && i.configured();
    }

    /** Why the investigator is not configured, naming the variable to set; empty when it is configured. */
    @ModelAttribute("aiNotConfiguredReason")
    public String aiNotConfiguredReason() {
        Investigator i = investigator.getIfAvailable();
        if (i == null) {
            return ProposalService.NOT_CONFIGURED;
        }
        return i.configured() ? "" : i.notConfiguredReason();
    }

    /** "OpenAI gpt-6.1-sol" while the investigator is configured, empty otherwise. */
    @ModelAttribute("aiInvestigator")
    public String aiInvestigator() {
        Investigator i = investigator.getIfAvailable();
        return i == null || !i.configured() ? "" : AiProvider.describe(i.provider(), i.model());
    }

    @ModelAttribute("currentUser")
    public String currentUser(Authentication authentication) {
        return authentication == null || !authentication.isAuthenticated() ? null : authentication.getName();
    }

    @ModelAttribute("currentRoles")
    public String currentRoles(Authentication authentication) {
        if (authentication == null) {
            return "";
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .collect(Collectors.joining(", "));
    }

    @ModelAttribute("businessDate")
    public LocalDate businessDate() {
        return businessDay.current();
    }

    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        return request.getRequestURI();
    }
}
