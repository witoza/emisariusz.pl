package co.postscriptum.db;

import co.postscriptum.RuntimeEnvironment;
import co.postscriptum.exception.InternalException;
import co.postscriptum.internal.MyConfiguration;
import co.postscriptum.service.AdminHelperService;
import co.postscriptum.test_data.TestDataCreator;
import co.postscriptum.test_data.TestUser;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;

@Service
@AllArgsConstructor
@Slf4j
public class DBDataInitializer {

    private final TestDataCreator testDataCreator;

    private final MyConfiguration configuration;

    private final AdminHelperService adminHelperService;

    private final DB db;

    private final RuntimeEnvironment env;

    @PostConstruct
    public void init() throws IOException {

        try {
            db.loadDb();
        } catch (Exception e) {
            log.warn("Problem with loading DB", e);
            if (env == RuntimeEnvironment.PROD) {
                //for prod make sure DB won't be recreated accidentally
                if (!"true".equalsIgnoreCase(System.getProperty("createDb"))) {
                    throw new InternalException("DB can't be loaded, if you want to recreate BD, set -DcreateDb=true", e);
                }
            }
            createNewDB();
        }
    }

    public void createNewDB() throws IOException {
        log.info("Creating new DB");

        if (!db.hasAccountByUsername(configuration.getAdminEmail())) {
            db.insertUser(adminHelperService.createAdmin("passwd"));

            adminHelperService.addAdminNotification("Admin user has been created. Do change password now");
        }

        insertTestData();

        db.saveStub();
    }

    private void insertTestData() throws IOException {
        log.info("Inserting test data");
        for (TestUser testUser : testDataCreator.getTestUsers()) {
            if (!db.hasAccountByUsername(testUser.getUsername())) {
                db.insertUser(testUser.createUserData(adminHelperService.getAdminPublicKey()));
            } else {
                log.info("Test user {} already exists", testUser.getUsername());
            }
        }
    }

}
