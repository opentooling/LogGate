package com.opentooling.loggate.web;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The welcome page, for someone not signed in. Someone who is, arriving from
 * a bookmark or a link, is sent on to LogGate itself: the page offers only
 * "Sign in", which would read as though their session had been lost.
 */
@Controller
public class WelcomeController {

  @GetMapping("/welcome")
  String welcome(Principal signedIn) {
    // Spring Security answers with no principal for an anonymous request.
    return signedIn == null ? "forward:/welcome.html" : "redirect:/";
  }
}
