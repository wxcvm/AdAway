package org.adaway.model.root;

/** A single subshell preserves exit codes and never equates query errors with absence. */
public final class HijackCleanupCommands {
    private HijackCleanupCommands() { }
    public static String script(String chain) {
        if (chain == null || !chain.matches("[A-Z][A-Z0-9_]{0,27}"))
            throw new IllegalArgumentException("Invalid private chain");
        return "(\n"
                + "iptables -t nat -S >/dev/null 2>&1 || exit 2\n"
                + "removed=0\n"
                + "while :; do\n"
                + " iptables -t nat -C OUTPUT -j " + chain + " >/dev/null 2>&1\n"
                + " result=$?\n"
                + " [ \"$result\" -eq 1 ] && break\n"
                + " [ \"$result\" -eq 0 ] || exit 2\n"
                + " [ \"$removed\" -lt 32 ] || exit 2\n"
                + " iptables -t nat -D OUTPUT -j " + chain + " >/dev/null 2>&1 || exit 2\n"
                + " removed=$((removed + 1))\n"
                + "done\n"
                + "iptables -t nat -S " + chain + " >/dev/null 2>&1\n"
                + "result=$?\n"
                + "case \"$result\" in\n"
                + " 0) iptables -t nat -F " + chain + " >/dev/null 2>&1 && "
                + "iptables -t nat -X " + chain + " >/dev/null 2>&1 || exit 2 ;;\n"
                + " 1) ;;\n"
                + " *) exit 2 ;;\n"
                + "esac\n"
                + "iptables -t nat -C OUTPUT -j " + chain + " >/dev/null 2>&1\n"
                + "[ \"$?\" -eq 1 ] || exit 2\n"
                + "echo CLEAN\n)";
    }
}
