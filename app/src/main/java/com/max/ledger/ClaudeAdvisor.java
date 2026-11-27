package com.max.ledger;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;

import java.util.stream.Collectors;

/**
 * Sends the budget summary to Claude and returns its advice as plain text.
 * Runs on a background thread; never logs or returns the API key.
 */
final class ClaudeAdvisor {

    static final String MODEL = "claude-opus-5-5";

    static final String SYSTEM =
            "You are a practical personal budgeting coach inside a phone budgeting app called Ledger. "
            + "The user sends a JSON summary of their monthly budget: income range (lean and full month take-home), "
            + "bills with minimums and what they budget, credit cards with balances, APRs and limits, "
            + "spending categories with budget and spent, savings and total debt. All amounts are US dollars per month "
            + "unless named as a balance.\n\n"
            + "Give 3 to 6 concrete suggestions, most impact first. Use their actual numbers and names. "
            + "Cover what matters most of: whether a lean month covers everything, which debt to pay down first and roughly "
            + "how much interest that saves, categories that run over, and how big an emergency cushion to aim for. "
            + "If the data looks incomplete or odd (for example income of 0), say so in one line instead of guessing.\n\n"
            + "Write for a phone screen: under 250 words, plain text, one short line per suggestion starting with \"- \", "
            + "and bold only a key figure with **double asterisks**. No headings, tables or preamble. "
            + "Stay on budgeting and debt; don't recommend specific stocks, options, funds or trades.";

    static final class Result {
        final boolean ok;
        final String text;
        Result(boolean ok, String text) { this.ok = ok; this.text = text; }
    }

    static Result ask(String apiKey, String budgetJson) {
        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(16000L)
                    .system(SYSTEM)
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                    .addUserMessage("Here is my budget. What should I change?\n\n" + budgetJson)
                    .build();
            Message m = client.messages().create(params);
            boolean refused = m.stopReason().map(r -> r.equals(StopReason.REFUSAL)).orElse(false);
            String text = m.content().stream()
                    .flatMap(b -> b.text().stream())
                    .map(t -> t.text())
                    .collect(Collectors.joining("\n"))
                    .trim();
            if (refused) return new Result(false, "Claude declined to answer this one. Try again later.");
            if (text.isEmpty()) return new Result(false, "Claude sent back an empty answer. Try again.");
            return new Result(true, text);
        } catch (UnauthorizedException e) {
            return new Result(false, "Claude didn't accept the API key. Check it under Claude settings and try again.");
        } catch (PermissionDeniedException e) {
            return new Result(false, "This API key isn't allowed to use " + MODEL + ".");
        } catch (RateLimitException e) {
            return new Result(false, "Too many requests right now. Wait a minute and try again.");
        } catch (BadRequestException e) {
            return new Result(false, "Claude couldn't read the request (" + safeMsg(e) + ").");
        } catch (InternalServerException e) {
            return new Result(false, "Claude is busy or having trouble. Try again in a few minutes.");
        } catch (AnthropicServiceException e) {
            if (e.statusCode() == 402)
                return new Result(false, "Your Anthropic account is out of credit. Add credit in the Anthropic Console.");
            return new Result(false, "Claude returned an error (" + e.statusCode() + ").");
        } catch (AnthropicIoException e) {
            return new Result(false, "Couldn't reach Claude. Check your connection and try again.");
        } catch (Exception e) {
            return new Result(false, "Something went wrong asking Claude: " + safeMsg(e));
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    /** Error text without anything that looks like a key. */
    private static String safeMsg(Exception e) {
        String s = String.valueOf(e.getMessage());
        s = s.replaceAll("sk-ant-[A-Za-z0-9_\\-]+", "[key]");
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    private ClaudeAdvisor() {}
}
