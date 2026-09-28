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

/** REST API 端到端测试：航班 → 货物 → 方案 → 确认 → 临时卸货/替换（版本化）→ 关闭 → 卸载事件。 */
@SpringBootTest
@AutoConfigureMockMvc
class LoadPlanApiTest {

    @Autowired
    MockMvc mvc;

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createFlight(String flightNo) throws Exception {
        createFlight(flightNo, 10000, 10000);
    }

    private void createFlight(String flightNo, double fwdMax, double aftMax) throws Exception {
        mvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON).content("""
                {"flightNo":"%s","emptyWeight":1000,"emptyArm":10,"minCg":8,"maxCg":12,
                 "holds":[{"code":"FWD","maxWeight":%s,"maxVolume":500,"arm":5,"allowedCategories":[]},
                          {"code":"AFT","maxWeight":%s,"maxVolume":500,"arm":15,"allowedCategories":[]}]}
                """.formatted(flightNo, fwdMax, aftMax)))
                .andExpect(status().isCreated());
    }

    private void createUnit(String unitNo, double weight) throws Exception {
        mvc.perform(post("/api/cargo-units").contentType(MediaType.APPLICATION_JSON).content("""
                {"unitNo":"%s","weight":%s,"volume":10,"category":"GEN","incompatibleCategories":[]}
                """.formatted(unitNo, weight)))
                .andExpect(status().isCreated());
    }

    @Test
    void endToEndLoadPlanFlow() throws Exception {
        String s = suffix();
        String flightNo = "CA" + s;
        String unitNo = "U" + s;
        String planNo = "P" + s;

        createFlight(flightNo);
        createUnit(unitNo, 100);

        mvc.perform(post("/api/flights/{flightNo}/plans", flightNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"planNo":"%s","assignments":[{"unitNo":"%s","holdCode":"FWD"}]}
                        """.formatted(planNo, unitNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.currentVersionNo").value(1))
                .andExpect(jsonPath("$.versions", hasSize(1)));

        // 方案号幂等：重复创建返回既有方案
        mvc.perform(post("/api/flights/{flightNo}/plans", flightNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"planNo":"%s","assignments":[{"unitNo":"%s","holdCode":"FWD"}]}
                        """.formatted(planNo, unitNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        mvc.perform(post("/api/plans/{planNo}/confirm", planNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.versionNo").value(1));

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

    /** 临时卸货 + 替换货物：准备形成新版本草案，确认时原子切换，版本历史保留。 */
    @Test
    void lateOffloadWithReplacementCreatesAndConfirmsNewVersion() throws Exception {
        String s = suffix();
        String flightNo = "CE" + s;
        String u1 = "U1" + s;
        String u2 = "U2" + s;
        String u3 = "U3" + s;
        String planNo = "P" + s;

        createFlight(flightNo);
        createUnit(u1, 100);
        createUnit(u2, 100);
        createUnit(u3, 100);

        mvc.perform(post("/api/flights/{flightNo}/plans", flightNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"planNo":"%s","assignments":[{"unitNo":"%s","holdCode":"FWD"},
                                                        {"unitNo":"%s","holdCode":"AFT"}]}
                        """.formatted(planNo, u1, u2)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/plans/{planNo}/confirm", planNo)).andExpect(status().isOk());

        // 准备调整：卸下 u1，加入替换货物 u3 到 FWD（变更号幂等）
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/plans/{planNo}/adjust", planNo)
                            .contentType(MediaType.APPLICATION_JSON).content("""
                            {"changeNo":"CHG-1","assignments":[{"unitNo":"%s","holdCode":"FWD"}],
                             "unloadUnitNos":["%s"]}
                            """.formatted(u3, u1)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.versionNo").value(2))
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.unloadedUnitNos[0]").value(u1))
                    .andExpect(jsonPath("$.addedUnitNos[0]").value(u3));
        }

        // 准备阶段实际占用未切换：u1 仍锁在 FWD，u3 仍空闲
        mvc.perform(get("/api/cargo-units/{unitNo}/whereabouts", u1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"))
                .andExpect(jsonPath("$.holdCode").value("FWD"));
        mvc.perform(get("/api/cargo-units/{unitNo}/whereabouts", u3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
        // 当前生效版本仍为 v1
        mvc.perform(get("/api/plans/{planNo}", planNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersionNo").value(1));

        // 确认新版本：原子切换全部占用
        mvc.perform(post("/api/plans/{planNo}/confirm", planNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.versionNo").value(2));

        mvc.perform(get("/api/cargo-units/{unitNo}/whereabouts", u1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.planNo").doesNotExist());
        mvc.perform(get("/api/cargo-units/{unitNo}/whereabouts", u3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"))
                .andExpect(jsonPath("$.holdCode").value("FWD"))
                .andExpect(jsonPath("$.planNo").value(planNo));

        // 重复确认幂等：不再二次卸货
        mvc.perform(post("/api/plans/{planNo}/confirm", planNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.versionNo").value(2));
        mvc.perform(get("/api/cargo-units/{unitNo}/whereabouts", u1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));

        // 版本历史保留：v1 SUPERSEDED，v2 CONFIRMED；当前版本为 v2
        mvc.perform(get("/api/plans/{planNo}/versions", planNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].versionNo").value(1))
                .andExpect(jsonPath("$[0].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$[1].versionNo").value(2))
                .andExpect(jsonPath("$[1].status").value("CONFIRMED"));
        mvc.perform(get("/api/plans/{planNo}", planNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersionNo").value(2));
        mvc.perform(get("/api/plans/{planNo}/versions/2", planNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unloadedUnitNos[0]").value(u1))
                .andExpect(jsonPath("$.addedUnitNos[0]").value(u3));

        // 最终装机方案的舱位占用与重心已反映调整结果
        mvc.perform(get("/api/flights/{flightNo}/constraints", flightNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withinEnvelope").value(true))
                .andExpect(jsonPath("$.violations", hasSize(0)));
    }

    /** 调整导致超舱：准备阶段返回 422，不产生新版本，原配载不变。 */
    @Test
    void lateOffloadViolationReturns422AndKeepsOriginal() throws Exception {
        String s = suffix();
        String flightNo = "CF" + s;
        String u1 = "U1" + s;
        String u2 = "U2" + s;
        String planNo = "P" + s;

        createFlight(flightNo, 150, 10000); // FWD 上限 150
        createUnit(u1, 100);
        createUnit(u2, 100);

        mvc.perform(post("/api/flights/{flightNo}/plans", flightNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"planNo":"%s","assignments":[{"unitNo":"%s","holdCode":"FWD"}]}
                        """.formatted(planNo, u1)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/plans/{planNo}/confirm", planNo)).andExpect(status().isOk());

        // 再加入 100kg 到 FWD → 200 > 150
        mvc.perform(post("/api/plans/{planNo}/adjust", planNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"changeNo":"CHG-X","assignments":[{"unitNo":"%s","holdCode":"FWD"}],"unloadUnitNos":[]}
                        """.formatted(u2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[0]", containsString("超重")));

        // 只有 v1
        mvc.perform(get("/api/plans/{planNo}/versions", planNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    /** 航班关闭后调整草案不能确认：返回 409，卸下未发生。 */
    @Test
    void confirmAdjustAfterCloseReturns409() throws Exception {
        String s = suffix();
        String flightNo = "CG" + s;
        String u1 = "U1" + s;
        String planNo = "P" + s;

        createFlight(flightNo);
        createUnit(u1, 100);

        mvc.perform(post("/api/flights/{flightNo}/plans", flightNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"planNo":"%s","assignments":[{"unitNo":"%s","holdCode":"FWD"}]}
                        """.formatted(planNo, u1)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/plans/{planNo}/confirm", planNo)).andExpect(status().isOk());

        // 卸下唯一货物（空方案 CG=10 合法）
        mvc.perform(post("/api/plans/{planNo}/adjust", planNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"changeNo":"CHG-CLOSE","assignments":[],"unloadUnitNos":["%s"]}
                        """.formatted(u1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(2));

        mvc.perform(post("/api/flights/{flightNo}/close", flightNo)).andExpect(status().isOk());

        mvc.perform(post("/api/plans/{planNo}/confirm", planNo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("已关闭")));

        // 卸下未发生
        mvc.perform(get("/api/cargo-units/{unitNo}/whereabouts", u1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"))
                .andExpect(jsonPath("$.holdCode").value("FWD"));
        mvc.perform(get("/api/plans/{planNo}", planNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersionNo").value(1));
    }

    @Test
    void constraintViolationReturns422WithDetails() throws Exception {
        String s = suffix();
        String flightNo = "CB" + s;
        String u1 = "U1" + s;
        String u2 = "U2" + s;

        createFlight(flightNo, 150, 10000);
        createUnit(u1, 100);
        createUnit(u2, 100);

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
    void stalePlanConfirmReturns409() throws Exception {
        String s = suffix();
        String flightNo = "CC" + s;
        String unitNo = "U" + s;
        String planNo = "P" + s;

        createFlight(flightNo);
        createUnit(unitNo, 100);
        mvc.perform(post("/api/flights/{flightNo}/plans", flightNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"planNo":"%s","assignments":[{"unitNo":"%s","holdCode":"FWD"}]}
                        """.formatted(planNo, unitNo)))
                .andExpect(status().isOk());

        // 准备期间货物重量变化 → 旧版本不能确认
        mvc.perform(put("/api/cargo-units/{unitNo}/weight", unitNo)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"weight\":150}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/plans/{planNo}/confirm", planNo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("快照失效")));
    }
}
