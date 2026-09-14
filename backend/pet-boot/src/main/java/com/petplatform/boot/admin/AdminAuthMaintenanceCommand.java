package com.petplatform.boot.admin;

import com.petplatform.admin.biz.application.AdminAuthService;
import java.io.Console;
import java.util.Arrays;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Interactive, host-controlled maintenance only; never an HTTP reset or default account
 * initializer.
 */
@Component
@ConditionalOnProperty(
    prefix = "pet.auth.admin",
    name = {"enabled", "maintenance"},
    havingValue = "true")
public class AdminAuthMaintenanceCommand implements ApplicationRunner {
  private final AdminAuthService auth;
  private final ConfigurableApplicationContext context;

  public AdminAuthMaintenanceCommand(
      AdminAuthService auth, ConfigurableApplicationContext context) {
    this.auth = auth;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (context.getClass().getName().contains("WebServer"))
      throw new IllegalStateException("Maintenance requires web-application-type=none");
    Console console = System.console();
    if (console == null)
      throw new IllegalStateException(
          "Interactive private console required; credentials cannot be arguments");
    if (args.containsOption("password") || args.containsOption("token"))
      throw new IllegalArgumentException("Secrets in command arguments are forbidden");
    String operation = console.readLine("Operation (bootstrap/recover): ");
    String reason = console.readLine("Recovery/initialization reason: ");
    char[] password = console.readPassword("New password: ");
    try {
      if ("bootstrap".equals(operation)) {
        String account = console.readLine("Account: ");
        String name = console.readLine("Display name: ");
        long id = auth.bootstrap(account, name, password, reason);
        console.printf("Initialized operator %d. No HTTP default account was created.%n", id);
      } else if ("recover".equals(operation)) {
        long id = Long.parseLong(console.readLine("Existing super administrator ID: "));
        auth.beginMaintenance(reason);
        auth.recover(id, password, reason);
        console.printf("Recovered operator %d; prior credentials revoked.%n", id);
      } else throw new IllegalArgumentException("Unsupported maintenance operation");
    } finally {
      if (password != null) Arrays.fill(password, '\0');
      context.close();
    }
  }
}
