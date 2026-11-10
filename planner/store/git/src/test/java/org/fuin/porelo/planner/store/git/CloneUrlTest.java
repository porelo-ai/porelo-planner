package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CloneUrlTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://github.com/porelo-ai/porelo-planner.git",
                "https://git.example.org:8443/team/store",
                "ssh://git@git.example.org/team/store.git",
                "ssh://git.example.org:2222/team/store",
                "git@github.com:porelo-ai/porelo-planner.git",
                "git@git.example.org:/srv/git/store.git"
            })
    void aRemoteOverHttpsOrSshIsTaken(final String url) {
        assertThat(new CloneUrl(url).value())
                .as("a real clone URL passes unchanged")
                .isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "/srv/git/store.git",
                "./store",
                "file:///srv/git/store.git",
                "http://git.example.org/store.git",
                "git://git.example.org/store.git",
                "ext::sh -c touch% /tmp/pwned",
                "-uhelp",
                "ssh://-oProxyCommand=touch/x",
                "git@-oProxyCommand=x:store",
                "https://git.example.org/../../etc",
                "https://git.example.org/store.git?x=1",
                "https://git.example.org/a b",
                "https://git.example.org/store\n.git"
            })
    void aUrlThatRunsACommandReadsALocalPathOrGoesInClearTextIsRefused(final String url) {
        // Such a URL comes from a project.yml anyone with write access to the store can change.
        assertThatThrownBy(() -> new CloneUrl(url))
                .as("'%s' is refused", url)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://user:secret@git.example.org/store.git",
                "https://secret@git.example.org/store.git",
                "ssh://git:secret@git.example.org/store.git"
            })
    void aUrlWithAPasswordIsRefusedWithoutShowingIt(final String url) {
        // A password in a URL lands in the clone's configuration and in every error Git prints.
        assertThatThrownBy(() -> new CloneUrl(url))
                .as("'%s' is refused", url)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret");
    }

    @Test
    void aUrlPrintsAsItsHostAlone() {
        // The path may name a private project; a log line needs only the host.
        assertThat(new CloneUrl("ssh://git@git.example.org/team/secret-project.git"))
                .as("the URL's text")
                .hasToString("CloneUrl[git@git.example.org]");
    }
}
