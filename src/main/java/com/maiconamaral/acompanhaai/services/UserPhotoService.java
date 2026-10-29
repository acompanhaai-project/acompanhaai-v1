package com.maiconamaral.acompanhaai.services;

import com.maiconamaral.acompanhaai.dto.PhotoDTO;
import com.maiconamaral.acompanhaai.entities.UserPhoto;
import com.maiconamaral.acompanhaai.repositories.*;
import com.maiconamaral.acompanhaai.services.exceptions.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.io.*;
import java.util.Locale;

@Service
public class UserPhotoService {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private final UserPhotoRepository repository;
    private final UserRepository users;

    public UserPhotoService(UserPhotoRepository repository, UserRepository users) {
        this.repository = repository;
        this.users = users;
    }

    @Transactional
    public void replace(Long userId, MultipartFile file) {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new InvalidDataException("Envie uma imagem de até 2 MiB.");
        }
        PhotoDTO photo = decode(file);
        var entity = repository.findById(userId).orElseGet(() -> new UserPhoto(users.getReferenceById(userId)));
        entity.replace(photo.content(), photo.contentType());
        repository.save(entity);
    }

    @Transactional(readOnly = true)
    public PhotoDTO find(Long userId) {
        var photo = repository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Foto de perfil não cadastrada."));
        return new PhotoDTO(photo.getContent(), photo.getContentType());
    }

    @Transactional
    public void delete(Long userId) { repository.deleteById(userId); }

    private PhotoDTO decode(MultipartFile file) {
        // O formato é identificado pelos bytes, sem confiar no nome ou MIME enviado pelo cliente.
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(file.getBytes()))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new InvalidDataException("A foto deve ser uma imagem JPEG ou PNG válida.");
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("png")) {
                    throw new InvalidDataException("Somente imagens JPEG e PNG são aceitas.");
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 4_000_000) {
                    throw new InvalidDataException("A imagem deve ter no máximo 4 milhões de pixels.");
                }
                var image = reader.read(0);
                var output = new ByteArrayOutputStream();
                ImageIO.write(image, format, output);
                if (output.size() > MAX_BYTES) throw new InvalidDataException("A imagem processada excede 2 MiB.");
                return new PhotoDTO(output.toByteArray(), format.equals("jpeg") ? "image/jpeg" : "image/png");
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new InvalidDataException("Não foi possível ler a imagem enviada.");
        }
    }
}
