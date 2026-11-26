-- Recalibrate SFace default cosine threshold after fixing RGB [0..255] tensor preprocessing
UPDATE attendance_settings
SET face_match_threshold = 0.42,
    face_model_version = 'yunet-2023mar+sface-2021dec-rgb255'
WHERE face_match_threshold <= 0.37;
