import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { logger } from "firebase-functions/v2";
import { displayNameOf, sendToUser } from "./send";

/**
 * Turns a nudge into a notification on the other person's phone.
 *
 * Unlike a moment, this one carries a notification payload as well as data.
 * A nudge is meant to interrupt — that is the entire point of it — so it
 * should still appear in the tray when the app is closed, which a data-only
 * message would not do.
 */
export const onNudgeCreated = onDocumentCreated(
  "pairings/{pairingId}/nudges/{nudgeId}",
  async (event) => {
    const nudge = event.data?.data();
    if (!nudge) return;

    const { reminderId, reminderTitle, fromUid, toUid } = nudge;
    if (!fromUid || !toUid) {
      logger.warn("Nudge missing sender or recipient", {
        id: event.params.nudgeId,
      });
      return;
    }

    const senderName = await displayNameOf(fromUid);
    const who = senderName || "They";

    await sendToUser(toUid, {
      notification: {
        title: `${who} nudged you`,
        body: reminderTitle || "About something on your shared list",
      },
      data: {
        type: "nudge",
        reminderId: reminderId ?? "",
        pairingId: event.params.pairingId,
      },
      android: {
        priority: "high",
        notification: {
          channelId: "nudges",
          // Collapsing on the reminder id means repeated pokes about the same
          // item replace each other instead of stacking up a wall of them.
          tag: reminderId ?? "nudge",
        },
        ttl: 24 * 60 * 60 * 1000,
      },
    });
  },
);
