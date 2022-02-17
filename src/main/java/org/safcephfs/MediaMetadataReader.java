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
	private static final Map<Integer, String> NAME_MAPPING = new HashMap<>();

	private static final int TYPE_INT = 0;

	private static final Map<Integer, Integer> TYPE_MAPPING = new HashMap<>();

	static {
		NAME_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, ExifInterface.TAG_IMAGE_WIDTH);
		TYPE_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, TYPE_INT);

		NAME_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, ExifInterface.TAG_IMAGE_LENGTH);
		TYPE_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, TYPE_INT);

		// in ms
		NAME_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_DURATION, MediaMetadata.METADATA_KEY_DURATION);
		TYPE_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_DURATION, TYPE_INT);
	}

	public static final String METADATA_KEY_VIDEO = "android.media.metadata.video";

	// Not sure what formats MediaMetadataRetriever supports, let's try all videos
	public static boolean isSupportedMimeType(String mimeType) {
		return mimeType.startsWith("video/");
	}

	public static void getMetadata(Bundle metadata, FileDescriptor fd) {
		Bundle videoMetadata = new Bundle();
		MediaMetadataRetriever retriever = new MediaMetadataRetriever();
		retriever.setDataSource(fd);

		for (int key: NAME_MAPPING.keySet()) {
			String raw = retriever.extractMetadata(key);
			if (raw == null) {
				continue;
			}

			Integer type = TYPE_MAPPING.get(key);
			if (type == null) {
				videoMetadata.putString(NAME_MAPPING.get(key), raw);
			} else {
				switch (type) {
				case TYPE_INT:
					videoMetadata.putInt(NAME_MAPPING.get(key), Integer.parseInt(raw));
					break;
				}
			}
		}
		metadata.putBundle(METADATA_KEY_VIDEO, videoMetadata);
		String[] types = {METADATA_KEY_VIDEO};
		metadata.putStringArray(DocumentsContract.METADATA_TYPES, types);
	}
}
