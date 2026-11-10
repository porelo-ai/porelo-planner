package org.fuin.porelo.planner.app;

/** A second planner process in miniature: tries to take the lock and says whether it got it. */
final class LockProbe {

    private LockProbe() {}

    /**
     * Prints {@code locked} or {@code refused}.
     *
     * @param args The runtime directory.
     */
    public static void main(final String[] args) {
        try (AddressFile file = AddressFile.open(args[0])) {
            System.out.println("locked");
        } catch (IllegalStateException e) {
            System.out.println(
                    e.getMessage().contains("already runs") ? "refused" : e.getMessage());
        }
    }
}
