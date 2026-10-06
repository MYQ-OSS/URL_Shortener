package top.mayiqin.url_shortener.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FrontendControllerTest {
    @Test void rootAndAppServeVueEntryWithoutCatchingShortCodes() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FrontendController()).build();
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/app/"));
        mvc.perform(get("/app/")).andExpect(status().isOk()).andExpect(forwardedUrl("/app/index.html"));
    }
}
