package com.chris64233.cargoloadplan;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REST API 端到端测试：航班 → 货物 → 方案 → 确认 → 查询 → 关闭 → 卸载事件。 */
@SpringBootTest
@AutoConfigureMockMvc
class LoadPlanApiTest {

    @Autowired
    MockMvc mvc;

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void endToEndLoadPlanFlow() throws Exception {
        String s = suffix();
        String flightNo = "CA" + s;
        String unitNo = "U" + s;
        String planNo = "P" + s;

        mvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON).content("""
                {"flightNo":"%s","emptyWeight":1000,"emptyArm":10,"minCg":8,"maxCg":12,
                 "holds":[{"code":"FWD","maxWeight":10000,"maxVolume":500,"arm":5,"allowedCategories":["GEN"]},
                          {"code":"AFT","maxWeight":10000,"maxVolume":500,"arm":15,"allowedCategories":[]}]}
                """.formatted(flightNo)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.holds", hasSize(2)));

        mvc.perform(post("/api/cargo-units").contentType(MediaType.APPLICATION_JSON).content("""
                {"unitNo":"%s","weight":100,"volume":10,"category":"GEN","incompatibleCategories":[]}
                """.formatted(unitNo)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));

        mvc.perform(post("/api/flights/{flightNo}/plans", flightNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"planNo":"%s","assignments":[{"unitNo":"%s","holdCode":"FWD"}]}
                        """.formatted(planNo, unitNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // 方案号幂等：重复创建返回既有方案
        mvc.perform(post("/api/flights/{flightNo}/plans", flightNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"planNo":"%s","assignments":[{"unitNo":"%s","holdCode":"FWD"}]}
                        """.formatted(planNo, unitNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        mvc.perform(post("/api/plans/{planNo}/confirm", planNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        mvc.perform(get("/api/flights/{flightNo}/hold-usage", flightNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].holdCode").value("FWD"))
                .andExpect(jsonPath("$[0].usedWeight").value(100.0))
                .andExpect(jsonPath("$[0].loadedUnits[0]").value(unitNo));

        mvc.perform(get("/api/flights/{flightNo}/constraints", flightNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withinEnvelope").value(true))
                .andExpect(jsonPath("$.violations", hasSize(0)));

        mvc.perform(get("/api/cargo-units/{unitNo}/whereabouts", unitNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"))
                .andExpect(jsonPath("$.flightNo").value(flightNo))
                .andExpect(jsonPath("$.holdCode").value("FWD"))
                .andExpect(jsonPath("$.planNo").value(planNo));

        mvc.perform(post("/api/flights/{flightNo}/close", flightNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        // 关闭后方案不可修改
        mvc.perform(post("/api/plans/{planNo}/unload", planNo))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/flights/{flightNo}/unload-events", flightNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"unitNo":"%s","actualWeight":99.5}
                        """.formatted(unitNo)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planNo").value(planNo));

        mvc.perform(get("/api/cargo-units/{unitNo}/whereabouts", unitNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNLOADED"))
                .andExpect(jsonPath("$.unloadEvents", hasSize(1)));
    }

    @Test
    void constraintViolationReturns422WithDetails() throws Exception {
        String s = suffix();
        String flightNo = "CB" + s;
        String u1 = "U1" + s;
        String u2 = "U2" + s;

        mvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON).content("""
                {"flightNo":"%s","emptyWeight":1000,"emptyArm":10,"minCg":8,"maxCg":12,
                 "holds":[{"code":"FWD","maxWeight":150,"maxVolume":500,"arm":5,"allowedCategories":[]}]}
                """.formatted(flightNo)))
                .andExpect(status().isCreated());
        for (String u : new String[]{u1, u2}) {
            mvc.perform(post("/api/cargo-units").contentType(MediaType.APPLICATION_JSON).content("""
                    {"unitNo":"%s","weight":100,"volume":10,"category":"GEN"}
                    """.formatted(u)))
                    .andExpect(status().isCreated());
        }

        mvc.perform(post("/api/flights/{flightNo}/plans", flightNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"planNo":"P%s","assignments":[{"unitNo":"%s","holdCode":"FWD"},{"unitNo":"%s","holdCode":"FWD"}]}
                        """.formatted(s, u1, u2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations", hasSize(1)))
                .andExpect(jsonPath("$.violations[0]", containsString("超重")));
    }

    @Test
    void unknownResourcesReturn404() throws Exception {
        mvc.perform(get("/api/plans/NOPE")).andExpect(status().isNotFound());
        mvc.perform(get("/api/flights/NOPE")).andExpect(status().isNotFound());
        mvc.perform(get("/api/cargo-units/NOPE/whereabouts")).andExpect(status().isNotFound());
    }

    @Test
    void lateOffloadAndReplaceCreatesVersionAndSwitchesOnConfirm() throws Exception {
        String s = suffix();
        String flightNo = "CD" + s;
        String u1 = "U1" + s;
        String u2 = "U2" + s;
        String planNo = "P" + s;

        mvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON).content("""
                {"flightNo":"%s","emptyWeight":1000,"emptyArm":10,"minCg":8,"maxCg":12,
                 "holds":[{"code":"FWD","maxWeight":10000,"maxVolume":500,"arm":5,"allowedCategories":[]},
                          {"code":"AFT","maxWeight":10000,"maxVolume":500,"arm":15,"allowedCategories":[]}]}
                """.formatted(flightNo)))
                .andExpect(status().isCreated());
        for (String u : new String[]{u1, u2}) {
            mvc.perform(post("/api/cargo-units").contentType(MediaType.APPLICATION_JSON).content("""
                    {"unitNo":"%s","weight":100,"volume":10,"category":"GEN"}
                    """.formatted(u)))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/flights/{flightNo}/plans", flightNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"planNo":"%s","assignments":[{"unitNo":"%s","holdCode":"FWD"}]}
                        """.formatted(planNo, u1)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/plans/{planNo}/confirm", planNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersionNo").value(1))
                .andExpect(jsonPath("$.versions", hasSize(1)))
                .andExpect(jsonPath("$.versions[0].status").value("ACTIVE"));

        // 准备临时卸货 u1 + 替换 u2：只生成 PROPOSED 版本，占用仍在原版本
        mvc.perform(post("/api/plans/{planNo}/adjust", planNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"assignments":[{"unitNo":"%s","holdCode":"AFT"}],"unloadUnitNos":["%s"]}
                        """.formatted(u2, u1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersionNo").value(1))
                .andExpect(jsonPath("$.versions", hasSize(2)))
                .andExpect(jsonPath("$.versions[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.versions[1].status").value("PROPOSED"))
                .andExpect(jsonPath("$.versions[1].items[0].unitNo").value(u2));
        mvc.perform(get("/api/cargo-units/{unitNo}/whereabouts", u1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"));

        // 确认切换：版本 2 生效，版本 1 保留为 SUPERSEDED
        mvc.perform(post("/api/plans/{planNo}/adjust/confirm", planNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.currentVersionNo").value(2))
                .andExpect(jsonPath("$.versions[0].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.versions[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$.versions[0].items[0].unitNo").value(u1));

        mvc.perform(get("/api/cargo-units/{unitNo}/whereabouts", u1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.planNo").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/cargo-units/{unitNo}/whereabouts", u2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"))
                .andExpect(jsonPath("$.holdCode").value("AFT"))
                .andExpect(jsonPath("$.planNo").value(planNo));

        // 整组卸载幂等：重复调用都返回 CANCELLED
        mvc.perform(post("/api/plans/{planNo}/unload", planNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.versions", hasSize(2)));
        mvc.perform(post("/api/plans/{planNo}/unload", planNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void adjustWithUnavailableReplacementKeepsOriginalOnConfirm() throws Exception {
        String s = suffix();
        String flightNo = "CE" + s;
        String u1 = "U1" + s;
        String repl = "UR" + s;
        String planNo = "P" + s;
        String takePlan = "PT" + s;

        mvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON).content("""
                {"flightNo":"%s","emptyWeight":1000,"emptyArm":10,"minCg":8,"maxCg":12,
                 "holds":[{"code":"FWD","maxWeight":10000,"maxVolume":500,"arm":5,"allowedCategories":[]},
                          {"code":"AFT","maxWeight":10000,"maxVolume":500,"arm":15,"allowedCategories":[]}]}
                """.formatted(flightNo)))
                .andExpect(status().isCreated());
        for (String u : new String[]{u1, repl}) {
            mvc.perform(post("/api/cargo-units").contentType(MediaType.APPLICATION_JSON).content("""
                    {"unitNo":"%s","weight":100,"volume":10,"category":"GEN"}
                    """.formatted(u)))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/flights/{flightNo}/plans", flightNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"planNo":"%s","assignments":[{"unitNo":"%s","holdCode":"FWD"}]}
                        """.formatted(planNo, u1)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/plans/{planNo}/confirm", planNo)).andExpect(status().isOk());
        mvc.perform(post("/api/plans/{planNo}/adjust", planNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"assignments":[{"unitNo":"%s","holdCode":"AFT"}],"unloadUnitNos":["%s"]}
                        """.formatted(repl, u1)))
                .andExpect(status().isOk());

        // 替换货物被另一方案抢占
        mvc.perform(post("/api/flights/{flightNo}/plans", flightNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"planNo":"%s","assignments":[{"unitNo":"%s","holdCode":"AFT"}]}
                        """.formatted(takePlan, repl)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/plans/{planNo}/confirm", takePlan)).andExpect(status().isOk());

        // 确认调整冲突 409，原配载继续有效
        mvc.perform(post("/api/plans/{planNo}/adjust/confirm", planNo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("替换货物")));
        mvc.perform(get("/api/cargo-units/{unitNo}/whereabouts", u1))
                .andExpect(jsonPath("$.status").value("LOCKED"))
                .andExpect(jsonPath("$.holdCode").value("FWD"))
                .andExpect(jsonPath("$.planNo").value(planNo));
        mvc.perform(get("/api/plans/{planNo}", planNo))
                .andExpect(jsonPath("$.currentVersionNo").value(1))
                .andExpect(jsonPath("$.versions[0].status").value("ACTIVE"));
    }

    @Test
    void stalePlanConfirmReturns409() throws Exception {
        String s = suffix();
        String flightNo = "CC" + s;
        String unitNo = "U" + s;
        String planNo = "P" + s;

        mvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON).content("""
                {"flightNo":"%s","emptyWeight":1000,"emptyArm":10,"minCg":8,"maxCg":12,
                 "holds":[{"code":"FWD","maxWeight":10000,"maxVolume":500,"arm":5,"allowedCategories":[]}]}
                """.formatted(flightNo)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/cargo-units").contentType(MediaType.APPLICATION_JSON).content("""
                {"unitNo":"%s","weight":100,"volume":10,"category":"GEN"}
                """.formatted(unitNo)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/flights/{flightNo}/plans", flightNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"planNo":"%s","assignments":[{"unitNo":"%s","holdCode":"FWD"}]}
                        """.formatted(planNo, unitNo)))
                .andExpect(status().isOk());

        // 准备期间货物重量变化 → 旧方案不能确认
        mvc.perform(put("/api/cargo-units/{unitNo}/weight", unitNo)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"weight\":150}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/plans/{planNo}/confirm", planNo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("快照失效")));
    }
}
