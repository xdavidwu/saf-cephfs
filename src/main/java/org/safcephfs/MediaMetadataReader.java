package org.safcephfs;

import android.media.MediaMetadataRetriever;
import android.media.MediaMetadata;
import android.media.ExifInterface;
import android.os.Bundle;
import android.provider.DocumentsContract;

import java.io.FileDescriptor;
import java.util.HashMap;
import java.util.Map;

public final class MediaMetadataReader {
	private static final Map<Integer, String> NAME_MAPPING_VIDEO = new HashMap<>();
	private static final Map<Integer, String> NAME_MAPPING_AUDIO = new HashMap<>();

	private static final int TYPE_INT = 0;

	private static final Map<Integer, Integer> TYPE_MAPPING = new HashMap<>();

	static {
		NAME_MAPPING_VIDEO.put(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, ExifInterface.TAG_IMAGE_WIDTH);
		TYPE_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, TYPE_INT);

		NAME_MAPPING_VIDEO.put(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, ExifInterface.TAG_IMAGE_LENGTH);
		TYPE_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, TYPE_INT);

		// in ms
		NAME_MAPPING_VIDEO.put(MediaMetadataRetriever.METADATA_KEY_DURATION, MediaMetadata.METADATA_KEY_DURATION);
		NAME_MAPPING_AUDIO.put(MediaMetadataRetriever.METADATA_KEY_DURATION, MediaMetadata.METADATA_KEY_DURATION);
		TYPE_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_DURATION, TYPE_INT);

		NAME_MAPPING_AUDIO.put(MediaMetadataRetriever.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ARTIST);

		NAME_MAPPING_AUDIO.put(MediaMetadataRetriever.METADATA_KEY_COMPOSER, MediaMetadata.METADATA_KEY_COMPOSER);

		NAME_MAPPING_AUDIO.put(MediaMetadataRetriever.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_ALBUM);
	}

	public static final String METADATA_KEY_VIDEO = "android.media.metadata.video";
	public static final String METADATA_KEY_AUDIO = "android.media.metadata.audio";

	public static boolean isSupportedMimeType(String mimeType) {
		return isSupportedVideoMimeType(mimeType) ||
			isSupportedAudioMimeType(mimeType);
	}

	// Not sure what formats MediaMetadataRetriever supports, let's try all
	private static boolean isSupportedVideoMimeType(String mimeType) {
		return mimeType.startsWith("video/");
	}

	private static boolean isSupportedAudioMimeType(String mimeType) {
		return mimeType.startsWith("audio/");
	}

	public static void getMetadata(Bundle metadata, FileDescriptor fd,
			String mimeType) {
		String metadataType;
		Map<Integer, String> nameMapping;
		if (isSupportedVideoMimeType(mimeType)) {
			metadataType = METADATA_KEY_VIDEO;
			nameMapping = NAME_MAPPING_VIDEO;
		} else if (isSupportedAudioMimeType(mimeType)) {
			metadataType = METADATA_KEY_AUDIO;
			nameMapping = NAME_MAPPING_AUDIO;
		} else {
			return;
		}
		Bundle typeSpecificMetadata = new Bundle();
		MediaMetadataRetriever retriever = new MediaMetadataRetriever();
		retriever.setDataSource(fd);

		for (int key: nameMapping.keySet()) {
			String raw = retriever.extractMetadata(key);
			if (raw == null) {
				continue;
			}

			Integer type = TYPE_MAPPING.get(key);
			if (type == null) {
				typeSpecificMetadata.putString(nameMapping.get(key), raw);
			} else {
				switch (type) {
				case TYPE_INT:
					typeSpecificMetadata.putInt(nameMapping.get(key), Integer.parseInt(raw));
					break;
				}
			}
		}
		metadata.putBundle(metadataType, typeSpecificMetadata);
		String[] types = {metadataType};
		metadata.putStringArray(DocumentsContract.METADATA_TYPES, types);
	}
}
