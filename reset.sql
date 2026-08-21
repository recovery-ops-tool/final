UPDATE users SET password_hash = crypt('Admin@123', gen_salt('bf', 12)) WHERE email = 'recoverpro.offl@gmail.com';
