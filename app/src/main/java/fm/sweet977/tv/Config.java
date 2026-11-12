package fm.sweet977.tv;

/** Where the app connects. Change these two lines if the website moves. */
final class Config {
    static final String SITE = "https://sweet977.freehosting.dev/";
    static final String START = SITE + "led"; // a page address (not #led), so the host's security check can't drop it
    /** The GitHub project that builds this app; new versions are published there. */
    static final String REPO = "stephjoshph/sweet977-tv";

    private Config() {}
}
