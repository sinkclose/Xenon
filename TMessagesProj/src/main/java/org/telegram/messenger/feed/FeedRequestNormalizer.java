package org.telegram.messenger.feed;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

public abstract class FeedRequestNormalizer {
    private static final Field[] EMPTY_FIELDS = new Field[0];
    private static final ClassMetadata EMPTY_METADATA = new ClassMetadata(null, null, null, null, EMPTY_FIELDS);
    private static final ConcurrentHashMap<Class<?>, ClassMetadata> metadataCache = new ConcurrentHashMap<>();

    private static long mergeResolvedDialogIds(long a, long b) {
        if (a == 0) {
            return b;
        }
        if (b == 0 || a == b) {
            return a;
        }
        return 0L;
    }

    public static TLObject normalize(int account, TLObject object) {
        FeedController controller;
        if (object != null && (controller = FeedController.peekInstance(account)) != null
                && !controller.hasNoSyntheticIds()
                && object.getClass().getName().startsWith("org.telegram.tgnet.")) {
            ClassMetadata metadata = getMetadata(object);
            if (metadata.messageIdFields.length != 0 || metadata.invoiceField != null) {
                normalizeMessageIds(account, controller, object, metadata);
                normalizeInvoice(account, controller, getFieldValue(metadata.invoiceField, object));
            }
        }
        return object;
    }

    private static ClassMetadata getMetadata(Object obj) {
        if (obj == null) {
            return EMPTY_METADATA;
        }
        return metadataCache.computeIfAbsent(obj.getClass(), FeedRequestNormalizer::buildMetadata);
    }

    private static ClassMetadata buildMetadata(Class<?> cls) {
        Field[] fields;
        try {
            fields = cls.getFields();
        } catch (Exception unused) {
            fields = EMPTY_FIELDS;
        }
        Field requestPeerField = null;
        ArrayList<Field> messageIdFields = null;
        Field peerField = null;
        Field channelField = null;
        Field invoiceField = null;
        for (Field field : fields) {
            String name = field.getName();
            if ("from_peer".equals(name) && requestPeerField == null) {
                requestPeerField = field;
            } else if ("peer".equals(name) && peerField == null) {
                peerField = field;
            } else if ("channel".equals(name) && channelField == null) {
                channelField = field;
            } else if ("invoice".equals(name) && invoiceField == null) {
                invoiceField = field;
            }
            if (isMessageIdField(field)) {
                if (messageIdFields == null) {
                    messageIdFields = new ArrayList<>();
                }
                messageIdFields.add(field);
            }
        }
        return new ClassMetadata(requestPeerField != null ? requestPeerField : peerField, peerField, channelField, invoiceField,
                messageIdFields != null ? messageIdFields.toArray(new Field[0]) : EMPTY_FIELDS);
    }

    private static void normalizeMessageIds(int account, FeedController controller, Object obj) {
        normalizeMessageIds(account, controller, obj, getMetadata(obj));
    }

    private static void normalizeMessageIds(int account, FeedController controller, Object obj, ClassMetadata metadata) {
        Field requestPeerField = metadata.requestPeerField;
        long dialogId = getDialogId(requestPeerField, obj);
        if (dialogId == 0) {
            dialogId = getDialogId(metadata.peerField, obj);
        }
        if (dialogId == 0) {
            dialogId = getChannelDialogId(metadata.channelField, obj);
        }
        long resolvedDialogId = normalizeMessageIdFields(controller, obj, metadata);
        if (resolvedDialogId == 0 || resolvedDialogId == dialogId) {
            return;
        }
        if (requestPeerField != null) {
            setInputPeer(account, requestPeerField, obj, resolvedDialogId);
        } else if (metadata.channelField != null) {
            setInputChannel(account, metadata.channelField, obj, resolvedDialogId);
        }
    }

    private static long normalizeMessageIdFields(FeedController controller, Object obj, ClassMetadata metadata) {
        long resolved = 0;
        if (obj == null) {
            return 0L;
        }
        for (Field field : metadata.messageIdFields) {
            resolved = mergeResolvedDialogIds(resolved, normalizeMessageIdField(controller, obj, field));
        }
        return resolved;
    }

    private static boolean isMessageIdField(Field field) {
        if (field == null || Modifier.isStatic(field.getModifiers())) {
            return false;
        }
        String name = field.getName();
        return "id".equals(name) || "msg_id".equals(name) || name.endsWith("_msg_id");
    }

    private static void normalizeInvoice(int account, FeedController controller, Object obj) {
        if (obj instanceof TLRPC.TL_inputInvoiceMessage) {
            normalizeMessageIds(account, controller, obj);
        }
    }

    private static long normalizeMessageIdField(FeedController controller, Object obj, Field field) {
        try {
            Object value = field.get(obj);
            if (value instanceof Integer) {
                Integer id = (Integer) value;
                long resolvedDialogId = controller.resolveRealDialogId(id);
                if (resolvedDialogId == 0) {
                    return 0L;
                }
                field.setInt(obj, controller.resolveRealMessageId(resolvedDialogId, id));
                return resolvedDialogId;
            }
            if (!(value instanceof ArrayList)) {
                return 0L;
            }
            ArrayList<?> list = (ArrayList<?>) value;
            long resolved = 0;
            for (int i = 0; i < list.size(); i++) {
                try {
                    Object item = list.get(i);
                    if (item instanceof Integer) {
                        Integer id = (Integer) item;
                        long resolvedDialogId = controller.resolveRealDialogId(id);
                        if (resolvedDialogId != 0) {
                            setListInteger(list, i, controller.resolveRealMessageId(resolvedDialogId, id));
                            resolved = mergeResolvedDialogIds(resolved, resolvedDialogId);
                        }
                    }
                } catch (Exception unused) {
                    return resolved;
                }
            }
            return resolved;
        } catch (Exception unused) {
            return 0L;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setListInteger(ArrayList list, int index, int value) {
        list.set(index, value);
    }

    private static void setInputPeer(int account, Field field, Object obj, long dialogId) {
        if (account < 0) {
            return;
        }
        try {
            TLRPC.InputPeer inputPeer = MessagesController.getInstance(account).getInputPeer(dialogId);
            if (inputPeer != null) {
                field.set(obj, inputPeer);
            }
        } catch (Exception unused) {
        }
    }

    private static void setInputChannel(int account, Field field, Object obj, long dialogId) {
        if (account < 0 || dialogId >= 0) {
            return;
        }
        try {
            TLRPC.InputChannel inputChannel = MessagesController.getInstance(account).getInputChannel(-dialogId);
            if (inputChannel != null) {
                field.set(obj, inputChannel);
            }
        } catch (Exception unused) {
        }
    }

    private static long getDialogId(Field field, Object obj) {
        if (field == null) {
            return 0L;
        }
        try {
            Object value = field.get(obj);
            if (value instanceof TLRPC.InputPeer) {
                return DialogObject.getPeerDialogId((TLRPC.InputPeer) value);
            }
        } catch (Exception unused) {
        }
        return 0L;
    }

    private static long getChannelDialogId(Field field, Object obj) {
        Object value = getFieldValue(field, obj);
        if (value instanceof TLRPC.InputChannel) {
            return getInputChannelDialogId((TLRPC.InputChannel) value);
        }
        return 0L;
    }

    private static long getInputChannelDialogId(TLRPC.InputChannel inputChannel) {
        if (inputChannel == null) {
            return 0L;
        }
        long channelId = inputChannel.channel_id;
        if (channelId == 0) {
            return 0L;
        }
        return -channelId;
    }

    private static Object getFieldValue(Field field, Object obj) {
        if (field == null) {
            return null;
        }
        try {
            return field.get(obj);
        } catch (Exception unused) {
            return null;
        }
    }

    public static final class ClassMetadata {
        private final Field channelField;
        private final Field invoiceField;
        private final Field[] messageIdFields;
        private final Field peerField;
        private final Field requestPeerField;

        private ClassMetadata(Field requestPeerField, Field peerField, Field channelField, Field invoiceField, Field[] messageIdFields) {
            this.requestPeerField = requestPeerField;
            this.peerField = peerField;
            this.channelField = channelField;
            this.invoiceField = invoiceField;
            this.messageIdFields = messageIdFields;
        }
    }
}
