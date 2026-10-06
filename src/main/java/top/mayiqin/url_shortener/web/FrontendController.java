package top.mayiqin.url_shortener.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Vue 使用 Hash 路由，后端只提供固定入口，避免与六位短码路由冲突。 */
@Controller
public class FrontendController {
    @GetMapping("/")
    public String home() {
        return "redirect:/app/";
    }

    @GetMapping({"/app", "/app/"})
    public String workspace() {
        return "forward:/app/index.html";
    }
}
