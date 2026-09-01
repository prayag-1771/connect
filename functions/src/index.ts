import { initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { logger } from "firebase-functions/v2";

initializeApp();

const db = getFirestore();

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
      logger.warn("Moment missing required fields", { id: event.params.momentId });
      return;
    }

    const pairingSnap = await db.collection("pairings").doc(pairingId).get();
    const members: string[] = pairingSnap.get("members") ?? [];
    const recipientId = members.find((m) => m !== senderId);

    // Normal while an invite is still outstanding — nobody to notify yet.
    if (!recipientId) return;

    const [recipientSnap, senderSnap] = await Promise.all([
      db.collection("users").doc(recipientId).get(),
      db.collection("users").doc(senderId).get(),
    ]);

    const token: string | undefined = recipientSnap.get("fcmToken");
    if (!token) {
      logger.info("Recipient has no FCM token yet", { recipientId });
      return;
    }

    try {
      await getMessaging().send({
        token,
        // Data-only. A notification payload would be handled by the system
        // tray while the app is backgrounded, and the client code that
        // updates the widget would never run.
        data: {
          type: "moment",
          storagePath,
          caption: caption ?? "",
          senderName: senderSnap.get("displayName") ?? "",
        },
        android: {
          priority: "high",
          // Past a few hours the photo isn't news any more. Better to drop it
          // than to have it surface at a confusing moment.
          ttl: 4 * 60 * 60 * 1000,
        },
      });
    } catch (error: unknown) {
      const code = (error as { code?: string })?.code;

      // The token died with the install it belonged to. Clearing it stops
      // every future send from failing the same way.
      if (code === "messaging/registration-token-not-registered") {
        await recipientSnap.ref.update({ fcmToken: FieldValue.delete() });
        logger.info("Cleared a dead FCM token", { recipientId });
        return;
      }

      logger.error("Push failed", { recipientId, code, error });
      throw error;
    }
  },
);
