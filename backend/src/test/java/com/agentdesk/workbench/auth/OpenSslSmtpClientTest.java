package com.agentdesk.workbench.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;

class OpenSslSmtpClientTest {
    @Test void sendsCredentialsThroughStdinInsteadOfProcessArguments() throws Exception {
        Path temp = Files.createTempDirectory("openssl-smtp-test-");
        Path executable = temp.resolve("fake-openssl.sh");
        Files.writeString(executable, """
                #!/bin/sh
                printf '%s\n' "$*" > "$0.args"
                printf '220 local test SMTP\r\n'
                state=ready
                while IFS= read -r line; do
                  line=${line%"$(printf '\r')"}
                  if [ "$state" = username ]; then
                    state=password; printf '334 UGFzc3dvcmQ6\r\n'
                  elif [ "$state" = password ]; then
                    state=ready; printf '235 authenticated\r\n'
                  elif [ "$state" = data ]; then
                    if [ "$line" = . ]; then state=ready; printf '250 queued\r\n'; fi
                  else
                    case "$line" in
                      'EHLO localhost') printf '250 local\r\n' ;;
                      'AUTH LOGIN') state=username; printf '334 VXNlcm5hbWU6\r\n' ;;
                      'MAIL FROM:'*) printf '250 sender ok\r\n' ;;
                      'RCPT TO:'*) printf '250 recipient ok\r\n' ;;
                      'DATA') state=data; printf '354 send data\r\n' ;;
                      'QUIT') printf '221 bye\r\n'; exit 0 ;;
                      *) printf '500 unexpected command\r\n'; exit 2 ;;
                    esac
                  fi
                done
                """);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));

        String password = "authorization-code-for-test";
        new OpenSslSmtpClient(executable).send("localhost", 465, "sender@qq.com", password,
                "recipient@example.com", "123456");

        String arguments = Files.readString(Path.of(executable + ".args"));
        assertThat(arguments).contains("s_client", "-connect localhost:465", "-servername localhost");
        assertThat(arguments).doesNotContain(password, "sender@qq.com", "recipient@example.com", "123456");
    }
}
