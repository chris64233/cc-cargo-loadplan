package com.chris64233.cargoloadplan;

import com.chris64233.cargoloadplan.repository.CargoUnitRepository;
import com.chris64233.cargoloadplan.repository.CompartmentRepository;
import com.chris64233.cargoloadplan.repository.FlightRepository;
import com.chris64233.cargoloadplan.repository.LoadPlanRepository;
import com.chris64233.cargoloadplan.repository.OffloadEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REST API 端到端流程测试。 */
@SpringBootTest
class LoadPlanApiTest {

    @Autowired
    WebApplicationContext context;
    @Autowired
    OffloadEventRepository offloadEventRepository;
    @Autowired
    LoadPlanRepository loadPlanRepository;
    @Autowired
    CargoUnitRepository cargoUnitRepository;
    @Autowired
    CompartmentRepository compartmentRepository;
    @Autowired
    FlightRepository flightRepository;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbcTemplate.update("update cargo_units set active_plan_id = null");
        offloadEventRepository.deleteAll();
        loadPlanRepository.deleteAll();
        cargoUnitRepository.deleteAll();
        compartmentRepository.deleteAll();
        flightRepository.deleteAll();
    }

    @Test
    void fullFlow_overHttp() throws Exception {
        long flightId = postJson("/api/flights", """
                {"flightNo":"CA2001","baseWeightKg":10000,"baseMoment":0,"minCg":-100,"maxCg":100}
                """, status().isCreated(), "$.id");
        long compartmentId = postJson("/api/flights/" + flightId + "/compartments", """
                {"code":"FWD","maxWeightKg":5000,"maxVolumeM3":30,"positionArm":5.0,"allowedCategories":["GENERAL"]}
                """, status().isCreated(), "$.id");
        long unitId = postJson("/api/cargo-units", """
                {"unitNo":"ULD901","weightKg":800,"volumeM3":5,"category":"GENERAL","incompatibleCategories":[]}
                """, status().isCreated(), "$.id");

        // 准备（两次相同请求验证幂等）
        String planBody = """
                {"planNo":"PLAN-901","flightId":%d,"items":[{"cargoUnitId":%d,"compartmentId":%d}]}
                """.formatted(flightId, unitId, compartmentId);
        mvc.perform(post("/api/load-plans").contentType(MediaType.APPLICATION_JSON).content(planBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
        mvc.perform(post("/api/load-plans").contentType(MediaType.APPLICATION_JSON).content(planBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planNo").value("PLAN-901"));
        assertThat(loadPlanRepository.findAll()).hasSize(1);

        // 确认（重复确认幂等）
        mvc.perform(post("/api/load-plans/PLAN-901/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(post("/api/load-plans/PLAN-901/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 舱位用量 / 约束计算 / 货物去向查询
        mvc.perform(get("/api/flights/" + flightId + "/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].compartmentCode").value("FWD"))
                .andExpect(jsonPath("$[0].usedWeightKg").value(800.0));
        mvc.perform(get("/api/flights/" + flightId + "/constraints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withinEnvelope").value(true));
        mvc.perform(get("/api/cargo-units/ULD901/whereabouts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALLOCATED"))
                .andExpect(jsonPath("$.planNo").value("PLAN-901"))
                .andExpect(jsonPath("$.compartmentCode").value("FWD"));

        // 关闭航班后只能记录实际卸载事件
        mvc.perform(post("/api/flights/" + flightId + "/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        mvc.perform(post("/api/load-plans/PLAN-901/offload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cargoUnitIds\":[%d]}".formatted(unitId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FLIGHT_CLOSED"));
        mvc.perform(post("/api/flights/" + flightId + "/offload-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitNo\":\"ULD901\",\"reason\":\"到达卸载\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planNo").value("PLAN-901"));
        mvc.perform(get("/api/cargo-units/ULD901/whereabouts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFLOADED"))
                .andExpect(jsonPath("$.offloadEvents[0].reason").value("到达卸载"));
    }

    @Test
    void constraintViolation_returns422WithCode() throws Exception {
        long flightId = postJson("/api/flights", """
                {"flightNo":"CA2002","baseWeightKg":10000,"baseMoment":0,"minCg":-100,"maxCg":100}
                """, status().isCreated(), "$.id");
        long compartmentId = postJson("/api/flights/" + flightId + "/compartments", """
                {"code":"FWD","maxWeightKg":100,"maxVolumeM3":30,"positionArm":5.0,"allowedCategories":["GENERAL"]}
                """, status().isCreated(), "$.id");
        long unitId = postJson("/api/cargo-units", """
                {"unitNo":"ULD902","weightKg":150,"volumeM3":5,"category":"GENERAL"}
                """, status().isCreated(), "$.id");

        mvc.perform(post("/api/load-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"planNo":"PLAN-902","flightId":%d,"items":[{"cargoUnitId":%d,"compartmentId":%d}]}
                                """.formatted(flightId, unitId, compartmentId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONSTRAINT_VIOLATION"));
    }

    @Test
    void missingResource_returns404() throws Exception {
        mvc.perform(get("/api/load-plans/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private long postJson(String url, String body,
                          org.springframework.test.web.servlet.ResultMatcher status,
                          String idJsonPath) throws Exception {
        MvcResult result = mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status)
                .andExpect(jsonPath(idJsonPath).exists())
                .andReturn();
        String content = result.getResponse().getContentAsString();
        return Long.parseLong(content.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }
}
