package com.example.serverregistry;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record ServerRequest(

        @NotBlank
        @Size(max = 255)
        String hostname,

        @NotBlank
        @Size(max = 45)
        @Pattern(regexp = ServerRequest.IP_ADDRESS, message = "must be a valid IP address")
        String ipAddress,

        @NotBlank
        @Size(max = 30)
        String serverType,

        @Min(1)
        @Max(65535)
        Integer port
) {

    /**
     * Normalises before validation runs, so "db", " DB " and "Db" all become the single
     * stored value "DB", and a whitespace-only type collapses to "" and fails {@code @NotBlank}.
     */
    public ServerRequest {
        serverType = serverType == null ? null : serverType.trim().toUpperCase(Locale.ROOT);
    }

    /** Rejects out-of-range octets such as 10.0.1.256. */
    private static final String IPV4 =
            "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}";

    private static final String IPV6 =
            "([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}"
            + "|([0-9a-fA-F]{1,4}:){1,7}:"
            + "|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}"
            + "|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}"
            + "|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}"
            + "|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}"
            + "|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}"
            + "|[0-9a-fA-F]{1,4}:(:[0-9a-fA-F]{1,4}){1,6}"
            + "|:((:[0-9a-fA-F]{1,4}){1,7}|:)"
            + "|fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]+"
            + "|::(ffff(:0{1,4})?:)?(" + IPV4 + ")"
            + "|([0-9a-fA-F]{1,4}:){1,4}:(" + IPV4 + ")";

    static final String IP_ADDRESS = "^(" + IPV4 + "|" + IPV6 + ")$";
}
