package rs.banka4.bank_service.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import rs.banka4.bank_service.domain.account.db.Account;
import rs.banka4.bank_service.integration.generator.UserGenerator;
import rs.banka4.bank_service.integration.seeder.TestDataSeeder;
import rs.banka4.bank_service.service.abstraction.JwtService;
import rs.banka4.testlib.integration.DbEnabledTest;

@SpringBootTest
@AutoConfigureMockMvc
@DbEnabledTest
public class SearchAllLoansTest {

    @Autowired
    private MockMvcTester m;

    @Autowired
    private UserGenerator userGen;

    @Qualifier("jwtServiceImpl")
    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objMapper;

    @Autowired
    private TestDataSeeder testDataSeeder;

    private String accessToken;

    @BeforeEach
    void setUp() {
        Account account = testDataSeeder.seedAccount();
        testDataSeeder.seedLoans(account);

        userGen.createEmployee(x -> x);
        var toks = userGen.doEmployeeLogin("john.doe@example.com", "test");
        accessToken = toks.accessToken();
    }

    @Test
    void searchAllLoansSuccessfully() throws Exception {
        m.get()
            .uri("/loans/search?page=0&size=10&type=MORTGAGE&status=APPROVED")
            .header("Authorization", "Bearer " + accessToken)
            .accept(MediaType.APPLICATION_JSON)
            .assertThat()
            .hasStatusOk()
            .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .bodyJson()
            .extractingPath("content")
            .asList()
            .satisfies(loans -> assertThat(loans).isNotEmpty());
    }
}
