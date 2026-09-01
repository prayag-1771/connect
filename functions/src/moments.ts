import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { logger } from "firebase-functions/v2";
import { displayNameOf, partnerOf, sendToUser } from "./send";

/**
 * Pushes a newly created moment to the other half of the pairing.
 *
 * This has to live on a server. Sending to another user's device requires
 * credentials that would be extractable from any client app that shipped them,
 * so the app itself can never be the one to send.
 */
export const onMomentCreated = onDocumentCreated(
  "moments/{momentId}",
  async (event) => {
    const moment = event.data?.data();
    if (!moment) return;

    const { pairingId, senderId, storagePath, caption } = moment;
    if (!pairingId || !senderId || !storagePath) {
      logger.warn("Moment missing required fields", {
        id: event.params.momentId,
      });
      return;
    }

    const recipientId = await partnerOf(pairingId, senderId);
    if (!recipientId) return;

    await sendToUser(recipientId, {
      // Data-only, deliberately. A notification payload would be handled by
      // the system tray while the app is backgrounded, and the client code
      // that updates the widget would never run at all.
      data: {
        type: "moment",
        storagePath,
        caption: caption ?? "",
        senderName: await displayNameOf(senderId),
      },
      android: {
        priority: "high",
        // Past a few hours the photo isn't news any more. Better to drop it
        // than to have it surface at a confusing moment.
        ttl: 4 * 60 * 60 * 1000,
      },
    });
  },
);
