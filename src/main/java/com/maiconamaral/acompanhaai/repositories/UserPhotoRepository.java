package com.maiconamaral.acompanhaai.repositories;

import com.maiconamaral.acompanhaai.entities.UserPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPhotoRepository extends JpaRepository<UserPhoto, Long> { }
