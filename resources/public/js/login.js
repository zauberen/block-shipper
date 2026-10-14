/*
 * Block Shipper - run and manage Minecraft servers from a web dashboard.
 * Copyright (C) 2025-2026 zauberen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

document.body.addEventListener('htmx:beforeRequest', function(evt) {
    if (evt.target.id === 'login-form') {
        const button = document.getElementById('login-button');
        const buttonText = button.querySelector('.button-text');
        button.disabled = true;
        buttonText.textContent = 'Logging in...';
                
        // Hide any existing error message
        const errorMsg = document.getElementById('error-message');
        errorMsg.classList.remove('show');
    }
});

document.body.addEventListener('htmx:afterRequest', function(evt) {
    if (evt.target.id === 'login-form') {
        const button = document.getElementById('login-button');
        const buttonText = button.querySelector('.button-text');
                
        if (evt.detail.xhr.status !== 200) {
            // Re-enable button and show error
            button.disabled = false;
            buttonText.textContent = 'Log In';
                    
            const errorMsg = document.getElementById('error-message');
            errorMsg.classList.add('show');
        }
    }
});
