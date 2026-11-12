package dev.backed.web;

import dev.backed.ledger.BusinessDay;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.LocalDate;
import java.util.stream.Collectors;

/** Values every page's layout needs: who is signed in, their roles, the business date, the current path. */
@ControllerAdvice
public class LayoutAdvice {

    private final BusinessDay businessDay;

    public LayoutAdvice(BusinessDay businessDay) {
        this.businessDay = businessDay;
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
