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
