package com.northwater;

import io.javalin.http.Context;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/** Renders the Thymeleaf pages and keeps the signed-in customer on them. */
public final class Pages {
    private final Ledger ledger;
    private final TemplateEngine engine;

    public Pages(Ledger ledger) {
        this.ledger = ledger;
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        TemplateEngine templates = new TemplateEngine();
        templates.setTemplateResolver(resolver);
        this.engine = templates;
    }

    private static final SecureRandom random = new SecureRandom();

    public String render(Context ctx, String template, Map<String, Object> model) {
        Map<String, Object> data = new HashMap<>(model);
        data.put("csrf", ensureCsrf(ctx));
        data.put("user", userView(ctx));
        data.put("flash", popFlash(ctx));
        data.putIfAbsent("active", "");
        return engine.process(template, new org.thymeleaf.context.Context(Locale.US, data));
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Views.UserView userView(Context ctx) {
        Ledger.User user = currentUser(ctx);
        if (user == null) {
            return null;
        }
        Views.UserView view = new Views.UserView();
        view.fullName = user.fullName();
        view.firstName = user.fullName().split(" ")[0];
        view.email = user.email();
        view.city = user.city();
        view.memberSince = Dates.monthYear(user.memberSince());
        return view;
    }

    Ledger.User currentUser(Context ctx) {
        Object raw = ctx.sessionAttribute("userId");
        if (!(raw instanceof Integer id)) {
            return null;
        }
        return ledger.findUser(id);
    }

    private static String ensureCsrf(Context ctx) {
        Object current = ctx.sessionAttribute("csrf");
        if (current instanceof String token && token.length() >= 16) {
            return token;
        }
        String token = newToken();
        ctx.sessionAttribute("csrf", token);
        return token;
    }

    private static Views.Flash popFlash(Context ctx) {
        Object raw = ctx.sessionAttribute("flash");
        if (!(raw instanceof Views.Flash flash)) {
            return null;
        }
        var session = ctx.req().getSession(false);
        if (session != null) {
            session.removeAttribute("flash");
        }
        return flash;
    }
}
