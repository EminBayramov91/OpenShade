package io.github.eminbayramov.openshade.shell;

/** Runs shell commands inside the Shizuku user service (shell uid). */
interface IShellService {
    int exec(String command) = 1;

    // Reserved transaction code Shizuku uses to tell the service to exit.
    void destroy() = 16777114;
}
