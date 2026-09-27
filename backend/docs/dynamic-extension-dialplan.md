How to Structurally Handle Multi-Tenant SIP DomainsTo ensure agent 1001 from Tenant A does not conflict with agent 1001 from Tenant B, you must use SIP Domains.When agents configure their softphones (like MicroSIP or Zoiper), they must log in using a full SIP URI matching their assigned domain name:Agent 1: 1001@tenantA.yourdialer.comAgent 2: 1001@tenantB.yourdialer.comThe Java Spring Boot Controller SolutionHere is exactly how your Spring Boot backend intercepts FreeSWITCH's dynamic requests to authenticate any username directly from your agents table.javapackage com.dialer.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class FreeSwitchXmlCurlController {

    // Inject your JPA repository or database service
    // @Autowired private AgentRepository agentRepository;

    @PostMapping(value = "/api/freeswitch/xml-handler", produces = MediaType.APPLICATION_XML_VALUE)
    public String handleFreeSwitchLookup(@RequestParam Map<String, String> params) {
        
        String section = params.get("section");   // e.g., "directory" (for registration/auth)
        String purpose = params.get("purpose");   // e.g., "gateways" or "network-list"
        String username = params.get("user");      // e.g., "1001"
        String domain = params.get("domain");      // e.g., "tenantA.yourdialer.com"

        // Handle User Authentication / Registration (Directory Section)
        if ("directory".equals(section) && username != null && domain != null) {
            
            /* 
             DATABASE LOOKUP SIMULATION:
             In production, you would run a query like:
             Agent agent = agentRepository.findByUsernameAndTenantDomain(username, domain);
            */
            
            String dbPassword = "password123"; // Replace with agent.getPassword() from DB
            boolean userExists = true;          // Replace with checking if database record exists

            if (userExists) {
                // Return a dynamic XML string to FreeSWITCH authorizing the user
                return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                       "<document type=\"freeswitch/xml\">\n" +
                       "  <section name=\"directory\">\n" +
                       "    <domain name=\"" + domain + "\">\n" +
                       "      <user id=\"" + username + "\">\n" +
                       "        <params>\n" +
                       "          <param name=\"password\" value=\"" + dbPassword + "\"/>\n" +
                       "        </params>\n" +
                       "        <variables>\n" +
                       "          <variable name=\"user_context\" value=\"default\"/>\n" +
                       "        </variables>\n" +
                       "      </user>\n" +
                       "    </domain>\n" +
                       "  </section>\n" +
                       "</document>";
            }
        }

        // If the user isn't found in your DB, return an empty XML object so FreeSWITCH rejects them
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
               "<document type=\"freeswitch/xml\">\n" +
               "  <section name=\"result\">\n" +
               "    <result status=\"not found\" />\n" +
               "  </section>\n" +
               "</document>";
    }
}
Use code with caution.The Only Settings You Need to Change inside FreeSWITCHTo enable this global flexibility, you only need to configure FreeSWITCH to read domains dynamically.Open /etc/freeswitch/autoload_configs/xml_curl.conf.xml and ensure your bindings point to your endpoint:xml<binding name="directory_binding">
  <param name="gateway-url" value="http://YOUR_SPRING_BOOT_IP:8080/api/freeswitch/xml-handler" bindings="directory"/>
</binding>
Use code with caution.Turn off the static default XML domain configurations inside /etc/freeswitch/directory/default.xml so they don't override your dynamic database settings (you can move or rename the default directory files to keep a completely clean slate).Using this pattern, creating a new user or a new tenant is as simple as inserting a row into your database. FreeSWITCH will instantly recognize them without needing a reload command (reloadxml) or any direct server changes.Would you like to explore how to set up dynamic dialplan generation via mod_xml_curl for routing these tenants, or should we map out the Java logic to update agent status (Available, Busy) automatically when they accept dialer calls?